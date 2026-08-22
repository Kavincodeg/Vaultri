import { NextRequest, NextResponse } from 'next/server'
import crypto from 'crypto'
import { prisma } from '@/lib/prisma'

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
      const razorpayId: string = pl?.id ?? ''
      const notes = pl?.notes ?? {}
      const dealId: string = notes.dealId ?? ''
      const type: string = notes.type ?? ''

      if (razorpayId) {
        await prisma.payment.updateMany({
          where: { razorpayId },
          data: { status: 'paid' },
        })
      }

      // Update deal status: deposit paid -> in_progress
      if (dealId && type === 'deposit') {
        await prisma.deal.update({
          where: { id: dealId },
          data: { status: 'in_progress' },
        })
      }
    }

    return NextResponse.json({ received: true })
  } catch (err: any) {
    console.error('[/api/webhook]', err)
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}