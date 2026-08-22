import { NextRequest, NextResponse } from 'next/server'
import crypto from 'crypto'
import { prisma } from '@/lib/prisma'
import { logAudit } from '@/lib/audit'

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.text()
    const signature = req.headers.get('x-razorpay-signature') ?? ''
    const secret = process.env.RAZORPAY_WEBHOOK_SECRET ?? ''

    if (secret && secret !== 'REPLACE_WITH_WEBHOOK_SECRET') {
      const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex')
      if (expected !== signature) {
        return NextResponse.json({ error: 'Invalid signature' }, { status: 401 })
      }
    }

    const event = JSON.parse(rawBody)
    const eventType: string = event.event ?? ''

    if (eventType === 'payment_link.paid') {
      const pl = event.payload?.payment_link?.entity
      const paymentObj = event.payload?.payment?.entity
      const razorpayLinkId: string = pl?.id ?? ''
      const razorpayPaymentId: string = paymentObj?.id ?? ''
      const notes = pl?.notes ?? {}
      const dealId: string = notes.dealId ?? ''
      const type: string = notes.type ?? ''

      if (razorpayPaymentId) {
        const existing = await prisma.payment.findUnique({ where: { razorpayPaymentId } })
        if (existing) {
          return NextResponse.json({ received: true, idempotent: true })
        }
      }

      if (razorpayLinkId) {
        await prisma.payment.updateMany({
          where: { razorpayLinkId },
          data: { status: 'paid', razorpayPaymentId: razorpayPaymentId || undefined },
        })
      }

      if (dealId && type === 'deposit') {
        await prisma.deal.update({
          where: { id: dealId },
          data: { status: 'in_progress' },
        })
        await logAudit(dealId, 'deposit_received', 'Deposit payment confirmed via Razorpay webhook. Payment ID: ' + razorpayPaymentId)
      }
    }

    return NextResponse.json({ received: true })
  } catch (err: any) {
    console.error('[/api/webhook]', err)
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}