// Server-only runtime guard
if (typeof window !== 'undefined') {
  throw new Error('This module can only be loaded on the server')
}

import { getToken } from 'next-auth/jwt'
import type { NextRequest } from 'next/server'
import { cookies, headers } from 'next/headers'
import { UnauthorizedError } from '@/lib/core-api/server'

export class NeedsRefreshError extends Error {
  constructor(message = 'Session needs refresh') {
    super(message)
    this.name = 'NeedsRefreshError'
  }
}

/**
 * Returns the access token from the JWT cookie when it has at least 30 seconds left;
 * otherwise throws NeedsRefreshError. It must NEVER call core-api refresh.
 *
 * Pages call it; on NeedsRefreshError they redirect to /auth/refreshing?next=<same-origin path>.
 * Route handlers return 401 with JSON {"code":"session_refresh"} instead.
 */
export async function getAccessTokenOrThrow(
  req?: NextRequest | Request | { cookies?: any; headers?: any }
): Promise<string> {
  let effectiveReq: any = req
  if (!effectiveReq) {
    try {
      const cookieStore = cookies()
      const headerStore = headers()
      effectiveReq = {
        cookies: Object.fromEntries(cookieStore.getAll().map((c: any) => [c.name, c.value])),
        headers: Object.fromEntries(headerStore.entries()),
      }
    } catch {
      // Not in active RSC request context
    }
  }

  const secret = process.env.NEXTAUTH_SECRET
  const token = await getToken({
    req: effectiveReq,
    secret,
  })

  const now = Math.floor(Date.now() / 1000)
  const accessExpiresAt = (token?.accessExpiresAt as number) || 0
  const accessToken = token?.accessToken as string | undefined

  // Token must exist, not have error flag, and have at least 30 seconds left
  if (!token || !accessToken || token.error || accessExpiresAt - now < 30) {
    throw new NeedsRefreshError()
  }

  return accessToken
}

/**
 * Maps a core-api UnauthorizedError (token expired mid-request) to NeedsRefreshError.
 */
export function handleCoreApiAuthError<T>(fn: () => Promise<T>): Promise<T> {
  return fn().catch((err) => {
    if (err instanceof UnauthorizedError) {
      throw new NeedsRefreshError('Access token expired mid-request')
    }
    throw err
  })
}
