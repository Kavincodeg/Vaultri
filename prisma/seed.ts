import { PrismaClient } from '@prisma/client'

const prisma = new PrismaClient()

async function main() {
  // Seed a demo seller (NextAuth User + Seller row)
  const user = await prisma.user.upsert({
    where: { email: 'demo@seller.com' },
    update: {},
    create: {
      email: 'demo@seller.com',
      name: 'Demo Seller',
      emailVerified: new Date(),
    },
  })

  const seller = await prisma.seller.upsert({
    where: { userId: user.id },
    update: {},
    create: {
      email: 'demo@seller.com',
      name: 'Demo Seller',
      userId: user.id,
    },
  })

  // Seed one sample deal so the dashboard is not empty
  const existingDeal = await prisma.deal.findFirst({ where: { sellerId: seller.id } })
  if (!existingDeal) {
    const dueDate = new Date()
    dueDate.setDate(dueDate.getDate() + 14)

    await prisma.deal.create({
      data: {
        sellerId: seller.id,
        customerName: 'Priya Sharma',
        customerPhone: '+919999900000',
        description: 'Custom gold necklace, ₹8,000, needs it by ' + dueDate.toDateString(),
        price: 800000, // in paise = ₹8,000
        depositPercent: 30,
        dueDate,
        status: 'deposit_pending',
        contractText: `TERMS OF SALE\n\nItem: Custom gold necklace\nTotal Price: ₹8,000\nDeposit (30%): ₹2,400 — due immediately\nBalance (70%): ₹5,600 — due on delivery\n\nDelivery Date: ${dueDate.toDateString()}\n\nCancellation Policy: If the customer cancels after work has begun, a cancellation fee of 50% of the total price (₹4,000) is due.\n\nLate Payment: Balances unpaid more than 7 days after delivery attract a 2% monthly late fee.\n\nDesign Rights: All designs remain the intellectual property of the seller until full payment is received.`,
      },
    })
  }

  console.log('Seed complete. Seller id:', seller.id)
}

main()
  .catch((e) => { console.error(e); process.exit(1) })
  .finally(async () => { await prisma.$disconnect() })
