import { describe, it, expect, vi, beforeEach } from 'vitest'
import { AuthHandler } from '../node_modules/next-auth/core/index.js'
import { encode, decode } from 'next-auth/jwt'
import { createHash, randomBytes } from 'crypto'
import { authOptions } from '@/lib/auth'

const secret = 'test-secret-at-least-32-chars-long-abc'
process.env.NEXTAUTH_SECRET = secret
process.env.NEXTAUTH_URL = 'http://localhost:3000'
process.env.CORE_API_URL = 'http://localhost:8080'
process.env.CORE_API_INTERNAL_SECRET = 'test-internal-secret-min-32-chars-long'

describe('NextAuth Core Handler Update Test', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    process.env.NEXTAUTH_SECRET = secret
    process.env.NEXTAUTH_URL = 'http://localhost:3000'
    process.env.CORE_API_URL = 'http://localhost:8080'
    process.env.CORE_API_INTERNAL_SECRET = 'test-internal-secret-min-32-chars-long'
  })

  it('drives real NextAuth core handler with POST to session, updates token and handles subsequent posts', async () => {
    const csrfToken = randomBytes(32).toString('hex')
    const csrfHash = createHash('sha256').update(`${csrfToken}${secret}`).digest('hex')
    const csrfCookie = `${csrfToken}|${csrfHash}`

    const now = Math.floor(Date.now() / 1000)
    const initialToken = {
      sub: 'seller-1',
      email: 'seller@example.com',
      accessToken: 'access-old-111',
      refreshToken: 'refresh-old-222',
      accessExpiresAt: now + 100, // 100s from expiry (< 300s)
      refreshExpiresAt: now + 600000,
      error: false,
    }

    const sessionToken = await encode({
      token: initialToken,
      secret,
    })

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/api/v1/auth/refresh')) {
        return {
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: 'access-new-333',
            refreshToken: 'refresh-new-444',
            expiresIn: 900,
            tokenType: 'Bearer',
          }),
        }
      }
      return { ok: true, status: 200, json: async () => ({}) }
    })
    global.fetch = fetchMock

    const jwtSpy = vi.spyOn(authOptions.callbacks!, 'jwt')

    // 1. First POST: near expiry token triggers update
    const req1 = {
      action: 'session',
      method: 'POST',
      headers: {
        host: 'localhost:3000',
        'content-type': 'application/json',
      },
      cookies: {
        'next-auth.csrf-token': csrfCookie,
        'next-auth.session-token': sessionToken,
      },
      body: {
        csrfToken,
        data: {},
      },
    }

    const optionsWithSecret = {
      ...authOptions,
      secret,
    }

    const res1 = await AuthHandler({ req: req1 as any, options: optionsWithSecret })
    expect(res1.status ?? 200).toBe(200)

    // Assert: jwt callback received trigger 'update'
    expect(jwtSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        trigger: 'update',
      })
    )

    // Assert: core-api refresh fetch called once with the refresh token from the cookie
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const refreshBody = JSON.parse(fetchMock.mock.calls[0][1].body)
    expect(refreshBody.refreshToken).toBe('refresh-old-222')

    // Assert: response Set-Cookie decodes to the NEW access and refresh tokens
    const sessionCookieObj = res1.cookies?.find((c: any) => c.name === 'next-auth.session-token')
    expect(sessionCookieObj).toBeDefined()
    const newSessionCookie = sessionCookieObj!.value

    const decodedNewToken = await decode({
      token: newSessionCookie,
      secret,
    })
    expect(decodedNewToken?.accessToken).toBe('access-new-333')
    expect(decodedNewToken?.refreshToken).toBe('refresh-new-444')

    // 2. Second POST with the new cookie (not near expiry) makes zero refresh calls
    const req2 = {
      action: 'session',
      method: 'POST',
      headers: {
        host: 'localhost:3000',
        'content-type': 'application/json',
      },
      cookies: {
        'next-auth.csrf-token': csrfCookie,
        'next-auth.session-token': newSessionCookie,
      },
      body: {
        csrfToken,
        data: {},
      },
    }

    const res2 = await AuthHandler({ req: req2 as any, options: optionsWithSecret })
    expect(res2.status ?? 200).toBe(200)
    // Refresh was NOT called again (total remains 1)
    expect(fetchMock).toHaveBeenCalledTimes(1)

    // 3. Third POST whose cookie token has error:true makes zero refresh calls
    const deadToken = {
      sub: 'seller-1',
      email: 'seller@example.com',
      accessToken: 'access-dead',
      refreshToken: 'refresh-dead',
      accessExpiresAt: now + 50, // near expiry
      refreshExpiresAt: now + 600000,
      error: true,
    }

    const deadSessionToken = await encode({
      token: deadToken,
      secret,
    })

    const req3 = {
      action: 'session',
      method: 'POST',
      headers: {
        host: 'localhost:3000',
        'content-type': 'application/json',
      },
      cookies: {
        'next-auth.csrf-token': csrfCookie,
        'next-auth.session-token': deadSessionToken,
      },
      body: {
        csrfToken,
        data: {},
      },
    }

    const res3 = await AuthHandler({ req: req3 as any, options: optionsWithSecret })
    expect(res3.status ?? 200).toBe(200)
    // Refresh was NOT called for dead token (total remains 1)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})
