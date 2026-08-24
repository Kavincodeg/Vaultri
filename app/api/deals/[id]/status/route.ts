import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { prisma } from '@/lib/prisma'

export async function GET(
  _req: NextRequest,
  { params }: { params: { id: string } }
) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }

    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ error: 'Not found' }, { status: 404 })

    const deal = await prisma.deal.findFirst({
      where: { id: params.id, sellerId: seller.id },
      select: {
        status: true,
        payments: { where: { type: 'deposit' }, select: { status: true }, take: 1 },
      },
    })

    if (!deal) return NextResponse.json({ error: 'Not found' }, { status: 404 })

    return NextResponse.json({
      dealStatus: deal.status,
      depositStatus: deal.payments[0]?.status ?? null,
    })
  } catch (err: any) {
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}
