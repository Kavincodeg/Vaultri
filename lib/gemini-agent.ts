// Suraksha — Server-side Agent with strict tool allowlist & Audit Logging
import * as Sentry from '@sentry/nextjs'
import { GoogleGenerativeAI, FunctionCallingMode } from '@google/generative-ai'
import { prisma } from '@/lib/prisma'
import { razorpay } from '@/lib/razorpay'
import nodemailer from 'nodemailer'
import { logAudit } from '@/lib/audit'

const genai = new GoogleGenerativeAI(process.env.GEMINI_API_KEY!)
const transporter = nodemailer.createTransport({
  service: 'gmail',
  auth: {
    user: process.env.GMAIL_USER,
    pass: process.env.GMAIL_APP_PASSWORD?.replace(/\s+/g, ''),
  },
})

// Retry helper — retries on 503 Service Unavailable with exponential backoff
async function withRetry<T>(fn: () => Promise<T>, retries = 3, delayMs = 2000): Promise<T> {
  for (let i = 0; i < retries; i++) {
    try {
      return await fn()
    } catch (err: any) {
      const is503 = err?.message?.includes('503') || err?.message?.includes('Service Unavailable') || err?.message?.includes('high demand')
      if (is503 && i < retries - 1) {
        console.warn(`[Agent] 503 on attempt ${i + 1}, retrying in ${delayMs}ms...`)
        await new Promise(r => setTimeout(r, delayMs))
        delayMs *= 2 // exponential backoff
        continue
      }
      throw err
    }
  }
  throw new Error('Max retries exceeded')
}
const DISCLAIMER =
  '\n\n[DISCLAIMER]\nThese terms are a plain-language summary to support your agreement, not a substitute for legal advice.'

const TOOLS = [
  {
    functionDeclarations: [
      {
        name: 'draft_contract',
        description:
          'Generate full contract terms. Decide deposit %, cancellationPercent, and write contractText with IP clause and late-payment clause based on the deal.',
        parameters: {
          type: 'OBJECT',
          properties: {
            contractText: { type: 'STRING' },
            depositPercent: { type: 'NUMBER' },
            cancellationPercent: { type: 'NUMBER' },
          },
          required: ['contractText', 'depositPercent', 'cancellationPercent'],
        },
      },
      {
        name: 'create_deposit_link',
        description:
          'Create Razorpay Payment Link for deposit. Must be called together with draft_contract.',
        parameters: {
          type: 'OBJECT',
          properties: {
            dealId: { type: 'STRING' },
            amount: { type: 'NUMBER' },
            customerName: { type: 'STRING' },
            description: { type: 'STRING' },
          },
          required: ['dealId', 'amount', 'customerName', 'description'],
        },
      },
      {
        name: 'create_cancellation_fee_link',
        description: 'Create Razorpay Payment Link for cancellation fee.',
        parameters: {
          type: 'OBJECT',
          properties: {
            dealId: { type: 'STRING' },
            percent: { type: 'NUMBER' },
            totalPrice: { type: 'NUMBER' },
            customerName: { type: 'STRING' },
            draftMessage: { type: 'STRING' },
          },
          required: ['dealId', 'percent', 'totalPrice', 'customerName', 'draftMessage'],
        },
      },
      {
        name: 'schedule_reminder',
        description: 'Write a Reminder row to DB and enqueue a background job.',
        parameters: {
          type: 'OBJECT',
          properties: {
            dealId: { type: 'STRING' },
            when: { type: 'STRING' },
            channel: { type: 'STRING' },
            message: { type: 'STRING' },
          },
          required: ['dealId', 'when', 'channel', 'message'],
        },
      },
      {
        name: 'send_reminder',
        description: 'Send scheduled reminder via Gmail email.',
        parameters: {
          type: 'OBJECT',
          properties: { reminderId: { type: 'STRING' } },
          required: ['reminderId'],
        },
      },
    ],
  },
]

async function exec_create_deposit_link(args: {
  dealId: string
  amount: number
  customerName: string
  description: string
}) {
  const appUrl = (process.env.NEXT_PUBLIC_APP_URL ?? '').replace(/\/$/, '')
  const link = await (razorpay.paymentLink.create as any)({
    amount: args.amount,
    currency: 'INR',
    description: 'Deposit: ' + args.description,
    customer: { name: args.customerName },
    notify: { email: false, sms: false },
    reminder_enable: false,
    // Redirect customer back to the deal page after successful payment
    callback_url: `${appUrl}/dashboard/${args.dealId}?paid=1`,
    callback_method: 'get',
    notes: { dealId: args.dealId, type: 'deposit' },
  })
  const payment = await prisma.payment.create({
    data: {
      dealId: args.dealId,
      razorpayLinkId: link.id,
      type: 'deposit',
      amount: args.amount,
      status: 'created',
      shortUrl: link.short_url ?? null,
    },
  })
  await prisma.deal.update({ where: { id: args.dealId }, data: { status: 'deposit_pending' } })
  await logAudit(
    args.dealId,
    'payment_link_created',
    'Created deposit payment link ' + link.id + ' for amount INR ' + args.amount / 100,
  )
  return { paymentId: payment.id, shortUrl: link.short_url }
}

async function exec_create_cancellation_fee_link(args: {
  dealId: string
  percent: number
  totalPrice: number
  customerName: string
  draftMessage: string
}) {
  const feeAmount = Math.round((args.totalPrice * args.percent) / 100)
  const deal = await prisma.deal.findUnique({ where: { id: args.dealId } })
  const link = await (razorpay.paymentLink.create as any)({
    amount: feeAmount,
    currency: 'INR',
    description:
      'Cancellation fee (' + args.percent + '%) for: ' + (deal?.description ?? 'custom order'),
    customer: { name: args.customerName },
    notify: { email: false, sms: false },
    reminder_enable: false,
    notes: { dealId: args.dealId, type: 'cancellation_fee' },
  })
  await prisma.payment.create({
    data: {
      dealId: args.dealId,
      razorpayLinkId: link.id,
      type: 'cancellation_fee',
      amount: feeAmount,
      status: 'created',
      shortUrl: link.short_url ?? null,
    },
  })
  await prisma.deal.update({ where: { id: args.dealId }, data: { status: 'cancelled' } })
  await logAudit(
    args.dealId,
    'cancellation_link_created',
    'Created cancellation fee payment link ' + link.id + ' (' + args.percent + '%)',
  )
  return { shortUrl: link.short_url, feeAmount, percent: args.percent, draftMessage: args.draftMessage }
}

async function exec_schedule_reminder(args: {
  dealId: string
  when: string
  channel: string
  message: string
}) {
  // Idempotency — don't double-schedule for the same deal
  const existing = await prisma.reminder.findFirst({
    where: { dealId: args.dealId, status: 'scheduled' },
  })
  if (existing) {
    await logAudit(args.dealId, 'reminder_skipped', 'Reminder already scheduled, skipping duplicate')
    return { reminderId: existing.id }
  }

  const reminder = await prisma.reminder.create({
    data: { dealId: args.dealId, channel: args.channel, message: args.message, status: 'scheduled' },
  })

  // No BullMQ enqueue needed — QStash cron (POST /api/cron/check-reminders)
  // polls every 12 h and fires any reminder whose deal.dueDate is within 3 days.

  await logAudit(
    args.dealId,
    'reminder_scheduled',
    'Scheduled ' + args.channel + ' reminder (QStash cron will deliver when deal due date is within 3 days)',
  )
  return { reminderId: reminder.id }
}

export async function exec_send_reminder(args: { reminderId: string }) {
  const reminder = await prisma.reminder.findUnique({
    where: { id: args.reminderId },
    include: { deal: { include: { seller: true, payments: true } } },
  })
  if (!reminder) throw new Error('Reminder not found')

  // Track attempt before sending so failures are recorded
  await prisma.reminder.update({
    where: { id: args.reminderId },
    data: { attempts: { increment: 1 } },
  })

  try {
    // Recompute amounts from real DB values — never trust stored message for financial figures
    const deal = reminder.deal
    const fmtINR = (paise: number) => 'INR ' + (paise / 100).toLocaleString('en-IN')
    const depositAmt = Math.round((deal.price * deal.depositPercent) / 100)
    const remainder = deal.price - depositAmt
    const dueDateStr = new Date(deal.dueDate).toLocaleDateString('en-IN', {
      day: 'numeric', month: 'long', year: 'numeric',
    })

    // Check real deposit payment status from DB — do NOT assume deposit is paid
    const depositPayment = (deal as any).payments?.find((p: any) => p.type === 'deposit')
    const depositPaid = depositPayment?.status === 'completed'
    const depositLine = depositPaid
      ? `Deposit paid: ${fmtINR(depositAmt)} (${deal.depositPercent}%)`
      : `Deposit (pending — not yet received): ${fmtINR(depositAmt)} (${deal.depositPercent}%)`

    const emailBody =
      `Hi, this is a payment reminder for your order with ${deal.customerName}.\n\n` +
      `Due date: ${dueDateStr}\n` +
      `Total price: ${fmtINR(deal.price)}\n` +
      `${depositLine}\n` +
      `Remaining balance due: ${fmtINR(remainder)}\n\n` +
      `Please arrange payment before the due date. Thank you!`

    if (!deal.customerEmail) throw new Error('Customer email is required to send reminder')

    await transporter.sendMail({
      from: process.env.GMAIL_USER,
      to: deal.customerEmail,
      subject: `Payment Reminder: ${deal.customerName} — due ${dueDateStr}`,
      text: emailBody,
    })
    await prisma.reminder.update({
      where: { id: args.reminderId },
      data: { sentAt: new Date(), status: 'sent' },
    })
    await logAudit(
      reminder.dealId,
      'reminder_sent',
      'Sent reminder to customer email: ' + reminder.deal.customerEmail,
    )
  } catch (err: any) {
    await prisma.reminder.update({
      where: { id: args.reminderId },
      data: { status: 'failed' },
    })
    await logAudit(
      reminder.dealId,
      'reminder_failed',
      'Failed to send reminder: ' + (err?.message ?? 'unknown error'),
    )
    // Alert Sentry — doc §9: every failed reminder send should raise an alert
    Sentry.captureException(err, {
      tags: { context: 'reminder_send' },
      extra: { reminderId: args.reminderId, dealId: reminder.dealId },
    })
    throw err // re-throw so QStash retries the request
  }

  return { sent: true, reminderId: args.reminderId }
}

export async function runNewDealAgent(input: {
  dealId: string
  description: string
  price: number
  dueDate: string
  customerName: string
  customerPhone?: string
}) {
  const model = genai.getGenerativeModel({
    model: 'gemini-2.5-flash',
    tools: TOOLS as any,
    toolConfig: { functionCallingConfig: { mode: FunctionCallingMode.ANY } },
  })

  const depositGuess = input.price >= 500000 ? 30 : input.price >= 200000 ? 25 : 20
  const prompt = [
    'You are Suraksha seller protection agent.',
    'A new deal has been created. You MUST call ALL THREE tools in sequence.',
    '',
    'Deal:',
    '- Customer: ' + input.customerName,
    '- Description: ' + input.description,
    '- Total price: INR ' + (input.price / 100).toLocaleString('en-IN') + ' (' + input.price + ' paise)',
    '- Due date: ' + input.dueDate,
    '- Deal ID: ' + input.dealId,
    '',
    'Step 1 - Call draft_contract: Decide deposit % (typical: ' +
      depositGuess +
      '%), cancellation fee % (typical: 50%), write contract text with IP clause and late-payment clause.',
    'Step 2 - Call create_deposit_link: dealId=' +
      input.dealId +
      ', amount=deposit amount in paise, customerName=' +
      input.customerName +
      ', description=' +
      input.description,
    'Step 3 - Call schedule_reminder: dealId=' +
      input.dealId +
      ', channel=email, write a payment reminder message.',
  ].join('\n')

  let result: any
  try {
    result = await withRetry(() => model.generateContent(prompt))
  } catch (err: any) {
    // Alert Sentry — doc §9: agent tool-call errors should raise an alert
    Sentry.captureException(err, {
      tags: { context: 'gemini_agent', agent: 'new_deal' },
      extra: { dealId: input.dealId },
    })
    throw err
  }

  const calls = result.response.functionCalls() ?? []
  if (!calls.length) throw new Error('Agent made no tool calls')

  const hasDraft = calls.some((c: any) => c.name === 'draft_contract')
  const hasDeposit = calls.some((c: any) => c.name === 'create_deposit_link')
  if (!hasDraft || !hasDeposit)
    throw new Error('Guardrail: must call draft_contract + create_deposit_link')

  const results: Record<string, unknown> = {}

  const draftCall = calls.find((c: any) => c.name === 'draft_contract')
  if (draftCall) {
    const a = draftCall.args as any
    const pct: number = a.depositPercent ?? 30
    const textWithDisclaimer = a.contractText + DISCLAIMER
    await prisma.deal.update({
      where: { id: input.dealId },
      data: { contractText: textWithDisclaimer, depositPercent: pct },
    })
    await logAudit(
      input.dealId,
      'contract_drafted',
      'Agent drafted contract with ' + pct + '% deposit and disclaimer',
    )
    results['draft_contract'] = { contractText: textWithDisclaimer, depositPercent: pct }
  }

  const depositCall = calls.find((c: any) => c.name === 'create_deposit_link')
  if (depositCall) {
    const pct = (results['draft_contract'] as any)?.depositPercent ?? 30
    const amt = Math.round((input.price * pct) / 100)
    results['create_deposit_link'] = await exec_create_deposit_link({
      dealId: input.dealId,
      amount: amt,
      customerName: input.customerName,
      description: input.description,
    })
  }

  const remCall = calls.find((c: any) => c.name === 'schedule_reminder')
  if (remCall) {
    const due = new Date(input.dueDate)
    const when = new Date(due)
    when.setDate(when.getDate() - 2)

    // Compute the actual remainder from real numbers — do NOT trust the AI's message
    const depositPct = (results['draft_contract'] as any)?.depositPercent ?? 30
    const depositAmt = Math.round((input.price * depositPct) / 100)
    const remainder = input.price - depositAmt
    const fmtINR = (paise: number) => 'INR ' + (paise / 100).toLocaleString('en-IN')

    const message =
      `Hi, this is a reminder that your payment for "${input.description}" is due on ` +
      due.toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' }) +
      `.\n\nRemaining balance: ${fmtINR(remainder)} (total ${fmtINR(input.price)} less deposit of ${fmtINR(depositAmt)}).\n\nPlease arrange payment before the due date. Thank you!`

    results['schedule_reminder'] = await exec_schedule_reminder({
      dealId: input.dealId,
      when: when.toISOString(),
      channel: 'email',
      message,
    })
  }

  return results
}

export async function runCancellationAgent(input: {
  dealId: string
  customerName: string
  totalPrice: number
  description: string
}) {
  const model = genai.getGenerativeModel({
    model: 'gemini-2.5-flash',
    tools: TOOLS as any,
    toolConfig: { functionCallingConfig: { mode: FunctionCallingMode.ANY } },
  })

  const prompt = [
    'Customer cancelled custom order after work commenced.',
    '- Customer: ' + input.customerName,
    '- Item: ' + input.description,
    '- Total Price: INR ' + (input.totalPrice / 100).toLocaleString('en-IN'),
    '- Deal ID: ' + input.dealId,
    '',
    'Call create_cancellation_fee_link. Decide fair fee % (typically 50%). Write draftMessage for seller.',
  ].join('\n')

  let result: any
  try {
    result = await withRetry(() => model.generateContent(prompt))
  } catch (err: any) {
    Sentry.captureException(err, {
      tags: { context: 'gemini_agent', agent: 'cancellation' },
      extra: { dealId: input.dealId },
    })
    throw err
  }

  const calls = result.response.functionCalls() ?? []
  const results: Record<string, unknown> = {}

  for (const call of calls) {
    if (call.name === 'create_cancellation_fee_link') {
      const a = call.args as any
      const res = await exec_create_cancellation_fee_link({
        dealId: input.dealId,
        percent: a.percent ?? 50,
        totalPrice: input.totalPrice,
        customerName: input.customerName,
        draftMessage: a.draftMessage ?? 'Cancellation fee applies per terms.',
      })
      results['create_cancellation_fee_link'] = res
      results['draftMessage'] = a.draftMessage
    }
  }

  return results
}
