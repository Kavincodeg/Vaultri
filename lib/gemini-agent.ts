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

    // If a customer email was provided on deal creation, send exclusively to that customer email.
    // If not provided, fallback to sending to the signed-in seller's email.
    const hasCustomerEmail = Boolean(deal.customerEmail && deal.customerEmail.trim().length > 0)
    const recipientEmail = hasCustomerEmail ? deal.customerEmail!.trim() : deal.seller.email

    if (!recipientEmail) throw new Error('Recipient email is required to send reminder')

    const emailBody =
      `Hi,\n\n` +
      `This is a friendly reminder that your payment is due soon.\n\n` +
      `If you've already paid, please ignore this message.\n\n` +
      `Thank you,\n` +
      `Vaultri Team`

    await transporter.sendMail({
      from: process.env.GMAIL_USER,
      to: recipientEmail,
      subject: `Payment Reminder: ${deal.customerName} — Vaultri`,
      text: emailBody,
    })
    await prisma.reminder.update({
      where: { id: args.reminderId },
      data: { sentAt: new Date(), status: 'sent' },
    })
    await logAudit(
      reminder.dealId,
      'reminder_sent',
      hasCustomerEmail
        ? `Sent reminder to customer email: ${recipientEmail}`
        : `Sent reminder to seller email (fallback): ${recipientEmail}`,
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

  let calls: any[] = []
  try {
    const result = await withRetry(() => model.generateContent(prompt))
    calls = result.response.functionCalls() ?? []
  } catch (err: any) {
    console.warn('[runNewDealAgent] Gemini tool call failed, using default protection terms fallback:', err?.message)
    Sentry.captureException(err, {
      tags: { context: 'gemini_agent', agent: 'new_deal' },
      extra: { dealId: input.dealId },
    })
  }

  const results: Record<string, unknown> = {}

  // 1. Contract Drafting
  const draftCall = calls.find((c: any) => c.name === 'draft_contract')
  let depositPct = depositGuess
  let contractText = ''

  if (draftCall) {
    const a = draftCall.args as any
    depositPct = a.depositPercent ?? depositGuess
    contractText = (a.contractText || '') + DISCLAIMER
  } else {
    // Fallback contract terms if LLM call was skipped or omitted
    const dueDateFormatted = new Date(input.dueDate).toLocaleDateString('en-IN', {
      day: 'numeric',
      month: 'long',
      year: 'numeric',
    })
    const fmtINR = (paise: number) => 'INR ' + (paise / 100).toLocaleString('en-IN')
    const depositAmt = Math.round((input.price * depositPct) / 100)

    contractText =
      `This contract confirms the custom agreement for: "${input.description}".\n\n` +
      `• Total Price: ${fmtINR(input.price)}\n` +
      `• Required Deposit (${depositPct}%): ${fmtINR(depositAmt)}\n` +
      `• Due / Delivery Date: ${dueDateFormatted}\n\n` +
      `Intellectual Property: All designs, mockups, and work-in-progress remain the property of the seller until full settlement.\n\n` +
      `Cancellation Policy: In the event of order cancellation, a 50% cancellation fee applies to cover labor and committed materials.\n\n` +
      `Late Payments: Final balance must be settled on or before the due date.` +
      DISCLAIMER
  }

  await prisma.deal.update({
    where: { id: input.dealId },
    data: { contractText, depositPercent: depositPct },
  })
  await logAudit(
    input.dealId,
    'contract_drafted',
    `Contract drafted with ${depositPct}% deposit terms`,
  )
  results['draft_contract'] = { contractText, depositPercent: depositPct }

  // 2. Create Razorpay Deposit Link
  const depositAmt = Math.round((input.price * depositPct) / 100)
  results['create_deposit_link'] = await exec_create_deposit_link({
    dealId: input.dealId,
    amount: depositAmt,
    customerName: input.customerName,
    description: input.description,
  })

  // 3. Schedule Reminder
  const due = new Date(input.dueDate)
  const when = new Date(due)
  when.setDate(when.getDate() - 2)
  const remainder = input.price - depositAmt
  const fmtINR = (paise: number) => 'INR ' + (paise / 100).toLocaleString('en-IN')

  const reminderMessage =
    `Hi, this is a reminder that your payment for "${input.description}" is due on ` +
    due.toLocaleDateString('en-IN', { day: 'numeric', month: 'long', year: 'numeric' }) +
    `.\n\nRemaining balance: ${fmtINR(remainder)}.\n\nPlease arrange payment before the due date. Thank you!`

  results['schedule_reminder'] = await exec_schedule_reminder({
    dealId: input.dealId,
    when: when.toISOString(),
    channel: 'email',
    message: reminderMessage,
  })

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

  let calls: any[] = []
  try {
    const result = await withRetry(() => model.generateContent(prompt))
    calls = result.response.functionCalls() ?? []
  } catch (err: any) {
    console.warn('[runCancellationAgent] Gemini tool call failed, using fallback:', err?.message)
    Sentry.captureException(err, {
      tags: { context: 'gemini_agent', agent: 'cancellation' },
      extra: { dealId: input.dealId },
    })
  }

  const results: Record<string, unknown> = {}
  const cancelCall = calls.find((c: any) => c.name === 'create_cancellation_fee_link')
  const percent = (cancelCall?.args as any)?.percent ?? 50
  const draftMessage = (cancelCall?.args as any)?.draftMessage ?? 'Cancellation fee applies per agreement terms.'

  const res = await exec_create_cancellation_fee_link({
    dealId: input.dealId,
    percent,
    totalPrice: input.totalPrice,
    customerName: input.customerName,
    draftMessage,
  })
  results['create_cancellation_fee_link'] = res
  results['draftMessage'] = draftMessage

  return results
}
