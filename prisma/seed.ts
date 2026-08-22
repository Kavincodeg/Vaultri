import { PrismaClient } from '@prisma/client'
import bcrypt from 'bcryptjs'

const prisma = new PrismaClient()

async function main() {
  const passwordHash = await bcrypt.hash('password123', 10)
  const seller = await prisma.seller.upsert({
    where: { email: 'demo@seller.com' },
    update: {},
    create: {
      email: 'demo@seller.com',
      passwordHash,
      name: 'Demo Seller',
      phone: '+919999900000',
      plan: 'free',
    },
  })

  const existingDeal = await prisma.deal.findFirst({ where: { sellerId: seller.id } })
  if (!existingDeal) {
    const dueDate = new Date()
    dueDate.setDate(dueDate.getDate() + 14)
    const deal = await prisma.deal.create({
      data: {
        sellerId: seller.id,
        customerName: 'Priya Sharma',
        customerPhone: '+919999900000',
        customerEmail: 'priya@example.com',
        description: 'Custom gold necklace, INR 8,000, needs it by ' + dueDate.toDateString(),
        price: 800000,
        depositPercent: 30,
        dueDate,
        status: 'deposit_pending',
        contractText: 'TERMS OF SALE\n\nParties: Seller and Priya Sharma\nItem: Custom gold necklace\nTotal Price: INR 8,000\n\n[DEPOSIT & PAYMENT]\nDeposit (30%): INR 2,400 ? due immediately\nBalance: Due on delivery, ' + dueDate.toDateString() + '\n\n[CANCELLATION POLICY]\nIf cancelled after work commences, a 50% cancellation fee (INR 4,000) applies.\n\n[LATE PAYMENT]\nBalances unpaid 7+ days after delivery attract a 2% monthly late fee.\n\n[DESIGN & IP]\nAll designs remain the seller intellectual property until full payment is received.\n\n[DISCLAIMER]\nThese terms are a plain-language summary to support your agreement, not a substitute for legal advice.',
      },
    })
    await prisma.auditLog.create({
      data: {
        dealId: deal.id,
        action: 'deal_created',
        detail: 'Created initial deal for Priya Sharma',
      },
    })
  }
  console.log('Seed complete. Seller id:', seller.id)
}

main()
  .catch(e => { console.error(e); process.exit(1) })
  .finally(() => prisma.$disconnect())