/**
 * Next.js Edge Middleware — rate limiting for auth + deal creation endpoints.
 *
 * Runs on every matched request BEFORE the route handler.
 * Uses @upstash/ratelimit with Upstash Redis REST API (edge-compatible).
 *
 * Falls back gracefully (allows all requests) if UPSTASH env vars are not set,
 * so local dev works without Redis.
 *
 * Doc ref: Section 9 — rate-limit the deal-creation and login endpoints.
 */
import { NextRequest, NextResponse } from 'next/server'
import { Ratelimit } from '@upstash/ratelimit'
import { Redis } from '@upstash/redis/cloudflare'

// Lazily build the limiter so missing env vars don't crash at import time
let _limiter: Ratelimit | null | undefined = undefined

function getLimiter(): Ratelimit | null {
  if (_limiter !== undefined) return _limiter
  const url = process.env.UPSTASH_REDIS_REST_URL
  const token = process.env.UPSTASH_REDIS_REST_TOKEN
  if (!url || !token) {
    _limiter = null
    return null
  }
  _limiter = new Ratelimit({
    redis: new Redis({ url, token }),
    // Shared limit across all protected routes: 20 requests / 60 seconds per IP
    // Individual route limits are enforced by the per-route limits in ratelimit.ts
    limiter: Ratelimit.slidingWindow(20, '60 s'),
    analytics: false,
    prefix: 'suraksha:mw',
  })
  return _limiter
}

function getIp(req: NextRequest): string {
  return (
    req.headers.get('x-forwarded-for')?.split(',')[0]?.trim() ??
    req.headers.get('x-real-ip') ??
    'anonymous'
  )
}

// Route-specific limits (requests per window)
const ROUTE_LIMITS: Record<string, { requests: number; window: `${number} s` }> = {
  '/api/auth/callback/credentials': { requests: 10, window: '60 s' },  // login
  '/api/auth/signup':               { requests: 5,  window: '600 s' }, // signup
  '/api/deals':                     { requests: 10, window: '60 s' },  // deal creation
  '/api/cancellation':              { requests: 10, window: '60 s' },  // cancellation
}

let _routeLimiters: Map<string, Ratelimit> | null = null

function getRouteLimiter(pathname: string): Ratelimit | null {
  const url = process.env.UPSTASH_REDIS_REST_URL
  const token = process.env.UPSTASH_REDIS_REST_TOKEN
  if (!url || !token) return null

  const cfg = ROUTE_LIMITS[pathname]
  if (!cfg) return null

  if (!_routeLimiters) _routeLimiters = new Map()
  if (!_routeLimiters.has(pathname)) {
    _routeLimiters.set(
      pathname,
      new Ratelimit({
        redis: new Redis({ url, token }),
        limiter: Ratelimit.slidingWindow(cfg.requests, cfg.window),
        analytics: false,
        prefix: 'suraksha:' + pathname.replace(/\//g, ':'),
      })
    )
  }
  return _routeLimiters.get(pathname)!
}

export async function middleware(req: NextRequest) {
  const { pathname } = req.nextUrl

  const limiter = getRouteLimiter(pathname)
  if (limiter) {
    const ip = getIp(req)
    const { success, limit, remaining } = await limiter.limit(ip)
    if (!success) {
      return new NextResponse(
        JSON.stringify({ error: 'Too many requests — please wait a moment' }),
        {
          status: 429,
          headers: {
            'Content-Type': 'application/json',
            'X-RateLimit-Limit': String(limit),
            'X-RateLimit-Remaining': String(remaining),
            'Retry-After': '60',
          },
        }
      )
    }
  }

  return NextResponse.next()
}

export const config = {
  matcher: [
    '/api/auth/callback/credentials',
    '/api/auth/signup',
    '/api/deals',
    '/api/cancellation',
  ],
}
