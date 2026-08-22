import { NextRequest, NextResponse } from 'next/server'
import { prisma } from '@/lib/prisma'
import bcrypt from 'bcryptjs'

export async function POST(req: NextRequest) {
  try {
    const { name, email, password, phone } = await req.json()
    if (!name || !email || !password) {
      return NextResponse.json({ error: 'Name, email, and password are required' }, { status: 400 })
    }
    const existing = await prisma.seller.findUnique({ where: { email } })
    if (existing) {
      return NextResponse.json({ error: 'An account with this email already exists' }, { status: 400 })
    }
    const passwordHash = await bcrypt.hash(password, 10)
    const seller = await prisma.seller.create({
      data: { name, email, passwordHash, phone: phone || null, plan: 'free' },
    })
    return NextResponse.json({ success: true, sellerId: seller.id }, { status: 201 })
  } catch (err: any) {
    return NextResponse.json({ error: err.message || 'Registration failed' }, { status: 500 })
  }
}