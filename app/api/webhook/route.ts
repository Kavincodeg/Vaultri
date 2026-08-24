import { NextRequest, NextResponse } from 'next/server'
import * as Sentry from '@sentry/nextjs'
import crypto from 'crypto'
import { prisma } from '@/lib/prisma'
import { logAudit } from '@/lib/audit'

export async function POST(req: NextRequest) {
  try {
    const rawBody = await req.text()
    const signature = req.headers.get('x-razorpay-signature') ?? ''
    const secret = process.env.RAZORPAY_WEBHOOK_SECRET ?? ''

    // Signature verification — non-negotiable in production (doc §6)
    if (secret) {
      const expected = crypto.createHmac('sha256', secret).update(rawBody).digest('hex')
      if (expected !== signature) {
        Sentry.captureMessage('Razorpay webhook signature mismatch', {
          level: 'warning',
          extra: { signature, path: '/api/webhook' },
        })
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

      // Idempotency — use razorpayPaymentId as unique key (doc §6)
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
        await logAudit(
          dealId,
          'deposit_received',
          'Deposit payment confirmed via Razorpay webhook. Payment ID: ' + razorpayPaymentId,
        )
      }

      if (dealId && type === 'cancellation_fee') {
        await logAudit(
          dealId,
          'cancellation_fee_received',
          'Cancellation fee payment confirmed via Razorpay webhook. Payment ID: ' + razorpayPaymentId,
        )
      }
    }

    return NextResponse.json({ received: true })
  } catch (err: any) {
    // Alert Sentry — doc §9: every failed payment webhook should raise an alert
    Sentry.captureException(err, {
      tags: { context: 'razorpay_webhook' },
    })
    console.error('[/api/webhook]', err)
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}
