import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { runCancellationAgent } from '@/lib/gemini-agent'

export async function POST(req: NextRequest) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    const { dealId, customerName, totalPrice, description } = await req.json()
    if (!dealId || !customerName || !totalPrice) return NextResponse.json({ error: 'Missing fields' }, { status: 400 })
    const result = await runCancellationAgent({ dealId, customerName, totalPrice, description })
    const res = result['create_cancellation_fee_link'] as any
    return NextResponse.json({
      shortUrl: res?.shortUrl,
      feeAmount: res?.feeAmount,
      percent: res?.percent,
      draftMessage: result['draftMessage'] as string ?? '',
    })
  } catch (err: any) {
    console.error('[/api/cancellation]', err)
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}