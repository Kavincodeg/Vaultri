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
  let body: any
  try {
    body = await req.json()
  } catch {
    return NextResponse.json(
      { error: 'Invalid signup data' },
      { status: 400 }
    )
  }

  if (!body || typeof body !== 'object') {
    return NextResponse.json(
      { error: 'Invalid signup data' },
      { status: 400 }
    )
  }

  const { name, email, password, phone } = body

  if (
    typeof name !== 'string' ||
    typeof email !== 'string' ||
    typeof password !== 'string' ||
    name.length === 0 ||
    email.length === 0 ||
    password.length === 0 ||
    name.length > 254 ||
    email.length > 254 ||
    password.length > 128
  ) {
    return NextResponse.json(
      { error: 'Invalid signup data' },
      { status: 400 }
    )
  }

  if (phone !== undefined && phone !== null) {
    if (typeof phone !== 'string' || phone.length > 32) {
      return NextResponse.json(
        { error: 'Invalid signup data' },
        { status: 400 }
      )
    }
  }

  try {
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
