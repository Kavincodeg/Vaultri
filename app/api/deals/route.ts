import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { prisma } from '@/lib/prisma'
import { runNewDealAgent } from '@/lib/gemini-agent'
import { logAudit } from '@/lib/audit'

export async function POST(req: NextRequest) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })

    const body = await req.json()
    const { description, customerName, customerPhone, customerEmail, price, dueDate } = body

    if (!description || !customerName || !price || !dueDate) {
      return NextResponse.json({ error: 'Missing required fields' }, { status: 400 })
    }

    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ error: 'Seller not found' }, { status: 404 })

    const deal = await prisma.deal.create({
      data: {
        sellerId: seller.id,
        customerName,
        customerPhone: customerPhone ?? null,
        customerEmail: customerEmail ?? null,
        description,
        price,
        depositPercent: 30,
        dueDate: new Date(dueDate),
        status: 'draft',
        contractText: '',
      },
    })

    await logAudit(deal.id, 'deal_created', 'Created deal for ' + customerName + ' (INR ' + (price/100) + ')')

    const agentResult = await runNewDealAgent({
      dealId: deal.id,
      description,
      price,
      dueDate,
      customerName,
      customerPhone,
    })

    return NextResponse.json({ dealId: deal.id, agentResult }, { status: 201 })
  } catch (err: any) {
    console.error('[/api/deals POST]', err?.message, err?.stack || err)
    return NextResponse.json({ error: err?.message || 'Failed to create deal' }, { status: 500 })
  }
}

export async function GET() {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ deals: [] })
    const deals = await prisma.deal.findMany({
      where: { sellerId: seller.id },
      orderBy: { createdAt: 'desc' },
      include: { payments: true, reminders: true, auditLogs: { orderBy: { createdAt: 'desc' } } },
    })
    return NextResponse.json({ deals })
  } catch (err: any) {
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}