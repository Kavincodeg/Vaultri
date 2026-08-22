// Suraksha Section 8 - Server-side Agent with strict tool allowlist & Audit Logging
import { GoogleGenerativeAI, FunctionCallingMode } from '@google/generative-ai'
import { prisma } from '@/lib/prisma'
import { razorpay } from '@/lib/razorpay'
import { Resend } from 'resend'
import { logAudit } from '@/lib/audit'

const genai = new GoogleGenerativeAI(process.env.GEMINI_API_KEY!)
const resend = new Resend(process.env.RESEND_API_KEY!)

const DISCLAIMER = '\n\n[DISCLAIMER]\nThese terms are a plain-language summary to support your agreement, not a substitute for legal advice.'

const TOOLS = [{
  functionDeclarations: [
    { name: 'draft_contract',
      description: 'Generate full contract terms. Decide deposit %, cancellationPercent, and write contractText with IP clause and late-payment clause based on the deal.',
      parameters: { type: 'OBJECT', properties: {
        contractText: { type: 'STRING' },
        depositPercent: { type: 'NUMBER' },
        cancellationPercent: { type: 'NUMBER' },
      }, required: ['contractText','depositPercent','cancellationPercent'] } },
    { name: 'create_deposit_link',
      description: 'Create Razorpay Payment Link for deposit. Must be called together with draft_contract.',
      parameters: { type: 'OBJECT', properties: {
        dealId: { type: 'STRING' },
        amount: { type: 'NUMBER' },
        customerName: { type: 'STRING' },
        description: { type: 'STRING' },
      }, required: ['dealId','amount','customerName','description'] } },
    { name: 'create_cancellation_fee_link',
      description: 'Create Razorpay Payment Link for cancellation fee.',
      parameters: { type: 'OBJECT', properties: {
        dealId: { type: 'STRING' },
        percent: { type: 'NUMBER' },
        totalPrice: { type: 'NUMBER' },
        customerName: { type: 'STRING' },
        draftMessage: { type: 'STRING' },
      }, required: ['dealId','percent','totalPrice','customerName','draftMessage'] } },
    { name: 'schedule_reminder',
      description: 'Write a Reminder row to DB.',
      parameters: { type: 'OBJECT', properties: {
        dealId: { type: 'STRING' },
        when: { type: 'STRING' },
        channel: { type: 'STRING' },
        message: { type: 'STRING' },
      }, required: ['dealId','when','channel','message'] } },
    { name: 'send_reminder',
      description: 'Send scheduled reminder via Resend email.',
      parameters: { type: 'OBJECT', properties: {
        reminderId: { type: 'STRING' },
      }, required: ['reminderId'] } },
  ],
}]

async function exec_create_deposit_link(args: { dealId: string; amount: number; customerName: string; description: string }) {
  const link = await (razorpay.paymentLink.create as any)({
    amount: args.amount, currency: 'INR',
    description: 'Deposit: ' + args.description,
    customer: { name: args.customerName },
    notify: { email: false, sms: false }, reminder_enable: false,
    notes: { dealId: args.dealId, type: 'deposit' },
  })
  const payment = await prisma.payment.create({ data: {
    dealId: args.dealId, razorpayLinkId: link.id, type: 'deposit',
    amount: args.amount, status: 'created',
  }})
  await prisma.deal.update({ where: { id: args.dealId }, data: { status: 'deposit_pending' } })
  await logAudit(args.dealId, 'payment_link_created', 'Created deposit payment link ' + link.id + ' for amount INR ' + (args.amount/100))
  return { paymentId: payment.id, shortUrl: link.short_url }
}

async function exec_create_cancellation_fee_link(args: { dealId: string; percent: number; totalPrice: number; customerName: string; draftMessage: string }) {
  const feeAmount = Math.round((args.totalPrice * args.percent) / 100)
  const deal = await prisma.deal.findUnique({ where: { id: args.dealId } })
  const link = await (razorpay.paymentLink.create as any)({
    amount: feeAmount, currency: 'INR',
    description: 'Cancellation fee (' + args.percent + '%) for: ' + (deal?.description ?? 'custom order'),
    customer: { name: args.customerName },
    notify: { email: false, sms: false }, reminder_enable: false,
    notes: { dealId: args.dealId, type: 'cancellation_fee' },
  })
  await prisma.payment.create({ data: {
    dealId: args.dealId, razorpayLinkId: link.id, type: 'cancellation_fee',
    amount: feeAmount, status: 'created',
  }})
  await prisma.deal.update({ where: { id: args.dealId }, data: { status: 'cancelled' } })
  await logAudit(args.dealId, 'cancellation_link_created', 'Created cancellation fee payment link ' + link.id + ' (' + args.percent + '%)')
  return { shortUrl: link.short_url, feeAmount, percent: args.percent, draftMessage: args.draftMessage }
}

async function exec_schedule_reminder(args: { dealId: string; when: string; channel: string; message: string }) {
  const reminder = await prisma.reminder.create({ data: { dealId: args.dealId, channel: args.channel, message: args.message, status: 'scheduled' } })
  await logAudit(args.dealId, 'reminder_scheduled', 'Scheduled ' + args.channel + ' reminder for ' + args.when)
  return { reminderId: reminder.id }
}

export async function exec_send_reminder(args: { reminderId: string }) {
  const reminder = await prisma.reminder.findUnique({ where: { id: args.reminderId }, include: { deal: { include: { seller: true } } } })
  if (!reminder) throw new Error('Reminder not found')
  await resend.emails.send({
    from: process.env.EMAIL_FROM ?? 'onboarding@resend.dev',
    to: reminder.deal.seller.email,
    subject: 'Reminder: Payment due for ' + reminder.deal.customerName,
    text: reminder.message,
  })
  await prisma.reminder.update({ where: { id: args.reminderId }, data: { sentAt: new Date(), status: 'sent', attempts: { increment: 1 } } })
  await logAudit(reminder.dealId, 'reminder_sent', 'Sent reminder to seller email: ' + reminder.deal.seller.email)
  return { sent: true, reminderId: args.reminderId }
}

export async function runNewDealAgent(input: { dealId: string; description: string; price: number; dueDate: string; customerName: string; customerPhone?: string }) {
  const model = genai.getGenerativeModel({ model: 'gemini-3.6-flash', tools: TOOLS as any, toolConfig: { functionCallingConfig: { mode: FunctionCallingMode.ANY } } })
  const depositGuess = input.price >= 500000 ? 30 : input.price >= 200000 ? 25 : 20
  const prompt = [
    'You are Suraksha seller protection agent.',
    'A new deal has been created. You MUST call ALL THREE tools in sequence.',
    '',
    'Deal:',
    '- Customer: ' + input.customerName,
    '- Description: ' + input.description,
    '- Total price: INR ' + (input.price/100).toLocaleString('en-IN') + ' (' + input.price + ' paise)',
    '- Due date: ' + input.dueDate,
    '- Deal ID: ' + input.dealId,
    '',
    'Step 1 - Call draft_contract: Decide deposit % (typical: ' + depositGuess + '%), cancellation fee % (typical: 50%), write contract text with IP clause and late-payment clause.',
    'Step 2 - Call create_deposit_link: dealId=' + input.dealId + ', amount=deposit amount in paise, customerName=' + input.customerName + ', description=' + input.description,
    'Step 3 - Call schedule_reminder: dealId=' + input.dealId + ', channel=email, write a payment reminder message.',
  ].join('\n')
  const result = await model.generateContent(prompt)
  const calls = result.response.functionCalls() ?? []
  if (!calls.length) throw new Error('Agent made no tool calls')
  const hasDraft = calls.some((c: any) => c.name === 'draft_contract')
  const hasDeposit = calls.some((c: any) => c.name === 'create_deposit_link')
  if (!hasDraft || !hasDeposit) throw new Error('Guardrail: must call draft_contract + create_deposit_link')
  const results: Record<string, unknown> = {}
  const draftCall = calls.find((c: any) => c.name === 'draft_contract')
  if (draftCall) {
    const a = draftCall.args as any
    const pct: number = a.depositPercent ?? 30
    const textWithDisclaimer = a.contractText + DISCLAIMER
    await prisma.deal.update({ where: { id: input.dealId }, data: { contractText: textWithDisclaimer, depositPercent: pct } })
    await logAudit(input.dealId, 'contract_drafted', 'Agent drafted contract with ' + pct + '% deposit and disclaimer')
    results['draft_contract'] = { contractText: textWithDisclaimer, depositPercent: pct }
  }
  const depositCall = calls.find((c: any) => c.name === 'create_deposit_link')
  if (depositCall) {
    const pct = (results['draft_contract'] as any)?.depositPercent ?? 30
    const amt = Math.round((input.price * pct) / 100)
    results['create_deposit_link'] = await exec_create_deposit_link({ dealId: input.dealId, amount: amt, customerName: input.customerName, description: input.description })
  }
  const remCall = calls.find((c: any) => c.name === 'schedule_reminder')
  if (remCall) {
    const a = remCall.args as any
    const due = new Date(input.dueDate)
    const when = new Date(due); when.setDate(when.getDate() - 2)
    results['schedule_reminder'] = await exec_schedule_reminder({ dealId: input.dealId, when: when.toISOString(), channel: 'email', message: a.message ?? ('Reminder: order due ' + due.toDateString()) })
  }
  return results
}

export async function runCancellationAgent(input: { dealId: string; customerName: string; totalPrice: number; description: string }) {
  const model = genai.getGenerativeModel({ model: 'gemini-3.6-flash', tools: TOOLS as any, toolConfig: { functionCallingConfig: { mode: FunctionCallingMode.ANY } } })
  const prompt = [
    'Customer cancelled custom order after work commenced.',
    '- Customer: ' + input.customerName,
    '- Item: ' + input.description,
    '- Total Price: INR ' + (input.totalPrice/100).toLocaleString('en-IN'),
    '- Deal ID: ' + input.dealId,
    '',
    'Call create_cancellation_fee_link. Decide fair fee % (typically 50%). Write draftMessage for seller.',
  ].join('\n')
  const result = await model.generateContent(prompt)
  const calls = result.response.functionCalls() ?? []
  const results: Record<string, unknown> = {}
  for (const call of calls) {
    if (call.name === 'create_cancellation_fee_link') {
      const a = call.args as any
      const res = await exec_create_cancellation_fee_link({ dealId: input.dealId, percent: a.percent ?? 50, totalPrice: input.totalPrice, customerName: input.customerName, draftMessage: a.draftMessage ?? 'Cancellation fee applies per terms.' })
      results['create_cancellation_fee_link'] = res
      results['draftMessage'] = a.draftMessage
    }
  }
  return results
}