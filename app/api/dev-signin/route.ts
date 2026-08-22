import { NextRequest, NextResponse } from 'next/server'
import { prisma } from '@/lib/prisma'

// DEV ONLY: auto-creates a session token for demo@seller.com
// This route is disabled in production
export async function GET(req: NextRequest) {
  if (process.env.NODE_ENV !== 'development') {
    return NextResponse.json({ error: 'Not available in production' }, { status: 403 })
  }

  const user = await prisma.user.findUnique({ where: { email: 'demo@seller.com' } })
  if (!user) return NextResponse.json({ error: 'Demo user not seeded. Run: npx prisma db seed' }, { status: 404 })

  // Create a VerificationToken that NextAuth will accept
  const token = 'demo-token-' + Date.now()
  const expires = new Date(Date.now() + 1000 * 60 * 10) // 10 min
  await prisma.verificationToken.upsert({
    where: { identifier_token: { identifier: 'demo@seller.com', token } },
    update: {},
    create: { identifier: 'demo@seller.com', token, expires },
  })

  // Redirect to NextAuth callback with the token
  const callbackUrl = req.nextUrl.origin + '/api/auth/callback/email?token=' + token + '&email=demo%40seller.com'
  return NextResponse.redirect(callbackUrl)
}