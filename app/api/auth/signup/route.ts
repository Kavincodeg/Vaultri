import { NextRequest, NextResponse } from 'next/server'
import {
  coreApiFetch,
  extractClientIp,
  RateLimitedError,
  ValidationError,
  ConflictError,
} from '@/lib/core-api/server'

interface CoreSellerResponse {
  id: string
  email: string
  name: string
  phone?: string | null
  plan?: string
  createdAt?: string
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json()
    const { name, email, password, phone } = body || {}

    if (!name || !email || !password) {
      return NextResponse.json(
        { error: 'Name, email, and password are required' },
        { status: 400 }
      )
    }

    const clientIp = extractClientIp(req.headers)

    const seller = await coreApiFetch<CoreSellerResponse>({
      method: 'POST',
      path: '/api/v1/auth/signup',
      body: { name, email, password, phone: phone || null },
      clientIp,
    })

    return NextResponse.json(
      {
        id: seller.id,
        email: seller.email,
        name: seller.name,
        phone: seller.phone ?? null,
        plan: seller.plan,
        createdAt: seller.createdAt,
      },
      { status: 201 }
    )
  } catch (err: unknown) {
    if (err instanceof ConflictError) {
      return NextResponse.json(
        { error: 'An account with this email already exists' },
        { status: 409 }
      )
    }
    if (err instanceof ValidationError) {
      return NextResponse.json(
        { error: 'Invalid signup data' },
        { status: 400 }
      )
    }
    if (err instanceof RateLimitedError) {
      return NextResponse.json(
        { error: 'Too many signup attempts, try again later' },
        {
          status: 429,
          headers: {
            'Retry-After': String(err.retryAfter || 60),
          },
        }
      )
    }
    return NextResponse.json(
      { error: 'Authentication service temporarily unavailable' },
      { status: 502 }
    )
  }
}
