import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { prisma } from '@/lib/prisma'
import { logAudit } from '@/lib/audit'

// Allowed statuses that can transition to completed
const COMPLETABLE = new Set(['deposit_paid', 'in_progress'])

export async function POST(
  req: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }

    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ error: 'Seller not found' }, { status: 404 })

    // Verify deal belongs to this seller
    const deal = await prisma.deal.findFirst({
      where: { id: params.id, sellerId: seller.id },
    })
    if (!deal) return NextResponse.json({ error: 'Deal not found' }, { status: 404 })

    if (deal.status === 'completed') {
      return NextResponse.json({ error: 'Deal is already marked as completed' }, { status: 400 })
    }
    if (deal.status === 'cancelled') {
      return NextResponse.json({ error: 'Cannot complete a cancelled deal' }, { status: 400 })
    }
    if (!COMPLETABLE.has(deal.status)) {
      return NextResponse.json(
        { error: 'Deal must be in deposit_paid or in_progress state to mark as completed' },
        { status: 400 }
      )
    }

    await prisma.deal.update({
      where: { id: deal.id },
      data: { status: 'completed' },
    })

    await logAudit(deal.id, 'deal_completed', 'Seller marked deal as completed')

    return NextResponse.json({ success: true })
  } catch (err: any) {
    console.error('[/api/deals/[id]/complete]', err)
    return NextResponse.json({ error: err.message ?? 'Internal server error' }, { status: 500 })
  }
}
