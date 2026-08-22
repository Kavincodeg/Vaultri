import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { prisma } from '@/lib/prisma'
import { runNewDealAgent } from '@/lib/gemini-agent'

export async function POST(req: NextRequest) {
  try {
    const session = await getServerSession()
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })

    const body = await req.json()
    const { description, customerName, customerPhone, price, dueDate } = body

    if (!description || !customerName || !price || !dueDate) {
      return NextResponse.json({ error: 'Missing required fields' }, { status: 400 })
    }

    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ error: 'Seller not found' }, { status: 404 })

    // Step 1: Create the Deal row with status=draft and empty contractText (agent fills it)
    const deal = await prisma.deal.create({
      data: {
        sellerId: seller.id,
        customerName,
        customerPhone: customerPhone ?? null,
        description,
        price,
        depositPercent: 30, // agent will overwrite this
        dueDate: new Date(dueDate),
        status: 'draft',
        contractText: '', // agent fills via draft_contract
      },
    })

    // Step 2: Run agent ? Section 6 guardrail: draft_contract + create_deposit_link together
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
    console.error('[/api/deals POST]', err)
    return NextResponse.json({ error: err.message ?? 'Internal server error' }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  try {
    const session = await getServerSession()
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
    if (!seller) return NextResponse.json({ deals: [] })
    const deals = await prisma.deal.findMany({
      where: { sellerId: seller.id },
      orderBy: { createdAt: 'desc' },
      include: { payments: true, reminders: true },
    })
    return NextResponse.json({ deals })
  } catch (err: any) {
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}