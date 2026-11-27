import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { authOptions, refreshAccessToken } from '@/lib/auth'
import { extractClientIp, coreApiFetch } from '@/lib/core-api/server'
import { POST as signupPost } from '@/app/api/auth/signup/route'
import { NextRequest } from 'next/server'

// Helper to check for token keys at any depth
function hasTokenProperties(obj: unknown): boolean {
  if (!obj || typeof obj !== 'object') return false
  const tokenRegex = /token|secret|jwt/i
  for (const [key, value] of Object.entries(obj)) {
    if (tokenRegex.test(key)) return true
    if (typeof value === 'object' && value !== null && hasTokenProperties(value)) {
      return true
    }
  }
  return false
}

describe('Core API Client & Auth Migration Tests', () => {
  const originalEnv = process.env

  beforeEach(() => {
    vi.resetModules()
    vi.restoreAllMocks()
    process.env = {
      ...originalEnv,
      CORE_API_URL: 'http://localhost:8080',
      CORE_API_INTERNAL_SECRET: 'test-internal-secret-min-32-chars-long',
    }
  })

  afterEach(() => {
    process.env = originalEnv
  })

  describe('1. Client IP Forwarding', () => {
    it('extracts valid single IPv4 and forwards with X-Internal-Secret', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'ok' }),
      })
      global.fetch = fetchMock

      const ip = extractClientIp({ 'cf-connecting-ip': '203.0.113.195' })
      expect(ip).toBe('203.0.113.195')

      await coreApiFetch({
        path: '/api/v1/test',
        clientIp: ip,
      })

      expect(fetchMock).toHaveBeenCalledTimes(1)
      const callArgs = fetchMock.mock.calls[0]
      const headers = callArgs[1].headers
      expect(headers['X-Forwarded-For']).toBe('203.0.113.195')
      expect(headers['X-Internal-Secret']).toBe('test-internal-secret-min-32-chars-long')
    })

    it('extracts valid single IPv6 and forwards with X-Internal-Secret', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'ok' }),
      })
      global.fetch = fetchMock

      const ip = extractClientIp({ 'x-real-ip': '2001:db8::8a2e:370:7334' })
      expect(ip).toBe('2001:db8::8a2e:370:7334')

      await coreApiFetch({
        path: '/api/v1/test',
        clientIp: ip,
      })

      const headers = fetchMock.mock.calls[0][1].headers
      expect(headers['X-Forwarded-For']).toBe('2001:db8::8a2e:370:7334')
      expect(headers['X-Internal-Secret']).toBe('test-internal-secret-min-32-chars-long')
    })

    it('rejects comma-separated list of IPs in x-forwarded-for and sends neither header', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'ok' }),
      })
      global.fetch = fetchMock

      const ip = extractClientIp({ 'x-forwarded-for': '1.2.3.4, 5.6.7.8' })
      expect(ip).toBeNull()

      await coreApiFetch({
        path: '/api/v1/test',
        clientIp: ip,
      })

      const headers = fetchMock.mock.calls[0][1].headers
      expect(headers['X-Forwarded-For']).toBeUndefined()
      expect(headers['X-Internal-Secret']).toBeUndefined()
    })

    it('rejects malformed IP string and sends neither header', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'ok' }),
      })
      global.fetch = fetchMock

      const ip = extractClientIp({ 'x-real-ip': 'not-an-ip' })
      expect(ip).toBeNull()

      await coreApiFetch({
        path: '/api/v1/test',
        clientIp: ip,
      })

      const headers = fetchMock.mock.calls[0][1].headers
      expect(headers['X-Forwarded-For']).toBeUndefined()
      expect(headers['X-Internal-Secret']).toBeUndefined()
    })

    it('handles missing IP and sends neither header', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({ status: 'ok' }),
      })
      global.fetch = fetchMock

      const ip = extractClientIp({})
      expect(ip).toBeNull()

      await coreApiFetch({
        path: '/api/v1/test',
        clientIp: null,
      })

      const headers = fetchMock.mock.calls[0][1].headers
      expect(headers['X-Forwarded-For']).toBeUndefined()
      expect(headers['X-Internal-Secret']).toBeUndefined()
    })
  })

  describe('2. Refresh Behavior & Concurrency', () => {
    it('does not refresh when token is not near expiry', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        accessExpiresAt: now + 300, // 5 min remaining
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res = await authOptions.callbacks?.jwt?.({ token, user: undefined })
      expect(fetchMock).not.toHaveBeenCalled()
      expect(res?.accessToken).toBe('access-1')
    })

    it('refreshes token once when near expiry', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          accessToken: 'access-2',
          refreshToken: 'refresh-2',
          expiresIn: 900,
          tokenType: 'Bearer',
        }),
      })
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        accessExpiresAt: now + 30, // Expires in 30s (< 60s)
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res = await authOptions.callbacks?.jwt?.({ token, user: undefined })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(res?.accessToken).toBe('access-2')
      expect(res?.refreshToken).toBe('refresh-2')
      expect(res?.error).toBe(false)
    })

    it('deduplicates 5 concurrent refreshes with the same token into exactly ONE call', async () => {
      let resolveFetch: (val: any) => void
      const fetchPromise = new Promise(resolve => {
        resolveFetch = resolve
      })

      const fetchMock = vi.fn().mockImplementation(() => fetchPromise)
      global.fetch = fetchMock

      const calls = [
        refreshAccessToken('same-refresh-token'),
        refreshAccessToken('same-refresh-token'),
        refreshAccessToken('same-refresh-token'),
        refreshAccessToken('same-refresh-token'),
        refreshAccessToken('same-refresh-token'),
      ]

      resolveFetch!({
        ok: true,
        status: 200,
        json: async () => ({
          accessToken: 'new-acc',
          refreshToken: 'new-ref',
          expiresIn: 900,
          tokenType: 'Bearer',
        }),
      })

      const results = await Promise.all(calls)
      expect(fetchMock).toHaveBeenCalledTimes(1)
      for (const r of results) {
        expect(r.accessToken).toBe('new-acc')
        expect(r.refreshToken).toBe('new-ref')
      }
    })

    it('marks token invalid when refresh returns 401 and enforces cooldown', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ error: 'Invalid refresh token' }),
      })
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'expired-or-revoked-token',
        accessExpiresAt: now + 10,
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res1 = await authOptions.callbacks?.jwt?.({ token, user: undefined })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(res1?.error).toBe(true)

      // Subsequent call within 30s cooldown should NOT trigger fetch again
      // @ts-expect-error NextAuth callback signature
      const res2 = await authOptions.callbacks?.jwt?.({ token: res1, user: undefined })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(res2?.error).toBe(true)
    })

    it('preserves existing token and sets cooldown on 5xx network failure', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: async () => ({ error: 'Bad Gateway' }),
      })
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-current',
        refreshToken: 'refresh-transient',
        accessExpiresAt: now + 20, // Not yet fully expired
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res1 = await authOptions.callbacks?.jwt?.({ token, user: undefined })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      // Access token preserved
      expect(res1?.accessToken).toBe('access-current')
      expect(res1?.error).toBe(true)

      // Next call does not hit fetch due to cooldown
      // @ts-expect-error NextAuth callback signature
      const res2 = await authOptions.callbacks?.jwt?.({ token: res1, user: undefined })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(res2?.accessToken).toBe('access-current')
    })
  })

  describe('3. Session Callback Token Safety', () => {
    it('does not leak accessToken or refreshToken at any depth in normal session', async () => {
      const token = {
        sub: 'seller-123',
        email: 'test@example.com',
        name: 'Test Seller',
        accessToken: 'super-secret-access-token',
        refreshToken: 'super-secret-refresh-token',
        accessExpiresAt: Math.floor(Date.now() / 1000) + 900,
        refreshExpiresAt: Math.floor(Date.now() / 1000) + 600000,
      }

      const session = await authOptions.callbacks?.session?.({
        session: { expires: new Date().toISOString() },
        token,
        user: undefined as any,
        newSession: undefined,
        trigger: 'update',
      })

      expect(session).toBeDefined()
      expect(session?.user).toEqual({
        sellerId: 'seller-123',
        email: 'test@example.com',
        name: 'Test Seller',
      })
      expect(hasTokenProperties(session)).toBe(false)
    })

    it('does not leak tokens in error session and marks error when token expired', async () => {
      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-123',
        email: 'test@example.com',
        name: 'Test Seller',
        accessToken: 'expired-access-token',
        refreshToken: 'failed-refresh-token',
        accessExpiresAt: now - 100, // Expired
        error: true,
      }

      const session = await authOptions.callbacks?.session?.({
        session: { expires: new Date().toISOString() },
        token,
        user: undefined as any,
        newSession: undefined,
        trigger: 'update',
      })

      expect((session as any)?.error).toBe(true)
      expect(session?.user).toBeUndefined()
      expect(hasTokenProperties(session)).toBe(false)
    })
  })

  describe('4. Authorize Credentials', () => {
    it('authorizes valid credentials, calls /auth/login then /me, and returns user with tokens', async () => {
      const fetchMock = vi
        .fn()
        .mockResolvedValueOnce({
          ok: true,
          status: 200,
          json: async () => ({
            accessToken: 'acc-jwt',
            refreshToken: 'ref-jwt',
            expiresIn: 900,
            tokenType: 'Bearer',
          }),
        })
        .mockResolvedValueOnce({
          ok: true,
          status: 200,
          json: async () => ({
            id: 'seller-uuid',
            email: 'seller@example.com',
            name: 'Seller Name',
            plan: 'PRO',
          }),
        })
      global.fetch = fetchMock

      const provider = authOptions.providers[0] as any
      const authorizeFn = provider.options?.authorize || provider.authorize
      const user = await authorizeFn({
        email: 'seller@example.com',
        password: 'correct-password',
      }, { headers: { 'cf-connecting-ip': '198.51.100.1' } })

      expect(user).toBeDefined()
      expect(user.id).toBe('seller-uuid')
      expect(user.email).toBe('seller@example.com')
      expect(user.accessToken).toBe('acc-jwt')
      expect(fetchMock).toHaveBeenCalledTimes(2)
    })

    it('returns null on 401 invalid credentials', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ error: 'Invalid credentials' }),
      })
      global.fetch = fetchMock

      const provider = authOptions.providers[0] as any
      const authorizeFn = provider.options?.authorize || provider.authorize
      const user = await authorizeFn({
        email: 'seller@example.com',
        password: 'wrong-password',
      })

      expect(user).toBeNull()
    })

    it('throws TOO_MANY_ATTEMPTS error on 429 rate limit', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 429,
        headers: new Headers({ 'retry-after': '45' }),
        json: async () => ({ error: 'Rate limit exceeded' }),
      })
      global.fetch = fetchMock

      const provider = authOptions.providers[0] as any
      const authorizeFn = provider.options?.authorize || provider.authorize
      await expect(
        authorizeFn({
          email: 'seller@example.com',
          password: 'password',
        })
      ).rejects.toThrow('TOO_MANY_ATTEMPTS')
    })

    it('throws SERVICE_UNAVAILABLE on core-api failure or timeout without leaking password', async () => {
      const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
      const fetchMock = vi.fn().mockRejectedValue(new Error('Connection refused'))
      global.fetch = fetchMock

      const provider = authOptions.providers[0] as any
      const authorizeFn = provider.options?.authorize || provider.authorize
      let thrownError: any
      try {
        await authorizeFn({
          email: 'secret-email@test.com',
          password: 'super-sensitive-password-1234',
        })
      } catch (err) {
        thrownError = err
      }

      expect(thrownError).toBeDefined()
      expect(thrownError.message).toBe('SERVICE_UNAVAILABLE')

      // Ensure password was not leaked into console
      for (const call of consoleErrorSpy.mock.calls) {
        const text = JSON.stringify(call)
        expect(text).not.toContain('super-sensitive-password-1234')
      }
    })
  })

  describe('5. Sign Out Event', () => {
    it('calls /auth/logout with refresh token and handles failure gracefully', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 204,
      })
      global.fetch = fetchMock

      const signOutEvent = authOptions.events?.signOut
      expect(signOutEvent).toBeDefined()

      await signOutEvent!({
        token: { refreshToken: 'rt-to-revoke' } as any,
        session: {} as any,
      })

      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(fetchMock.mock.calls[0][0]).toContain('/api/v1/auth/logout')
      expect(JSON.parse(fetchMock.mock.calls[0][1].body)).toEqual({
        refreshToken: 'rt-to-revoke',
      })
    })

    it('swallows errors if logout endpoint fails or times out', async () => {
      const fetchMock = vi.fn().mockRejectedValue(new Error('Network error on logout'))
      global.fetch = fetchMock

      const signOutEvent = authOptions.events?.signOut
      await expect(
        signOutEvent!({
          token: { refreshToken: 'rt-to-revoke' } as any,
          session: {} as any,
        })
      ).resolves.not.toThrow()
    })
  })

  describe('6. Signup Proxy Route', () => {
    it('successfully signs up via core-api (201)', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 201,
        json: async () => ({
          id: 'new-seller-uuid',
          email: 'new@seller.com',
          name: 'New Seller',
          phone: null,
          plan: 'FREE',
          createdAt: '2026-11-27T12:00:00Z',
        }),
      })
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'cf-connecting-ip': '203.0.113.50',
        },
        body: JSON.stringify({
          name: 'New Seller',
          email: 'new@seller.com',
          password: 'secure-password',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(201)
      const data = await res.json()
      expect(data.id).toBe('new-seller-uuid')
      expect(data.email).toBe('new@seller.com')
      expect(data.password).toBeUndefined()
    })

    it('maps 409 conflict when email already exists', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 409,
        json: async () => ({ message: 'Email already registered' }),
      })
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Duplicate',
          email: 'dup@seller.com',
          password: 'pass',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(409)
      const data = await res.json()
      expect(data.error).toBe('An account with this email already exists')
    })

    it('maps 400 validation error', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 400,
        json: async () => ({ message: 'Validation failed' }),
      })
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          email: 'bad',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(400)
    })

    it('maps 429 rate limit with Retry-After header', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 429,
        headers: new Headers({ 'retry-after': '30' }),
        json: async () => ({ message: 'Rate limit exceeded' }),
      })
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Spam',
          email: 'spam@seller.com',
          password: 'pass',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(429)
      expect(res.headers.get('retry-after')).toBe('30')
    })

    it('maps 5xx or core-api unavailability to 502 Bad Gateway', async () => {
      const fetchMock = vi.fn().mockRejectedValue(new Error('Connection refused'))
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Seller',
          email: 'seller@example.com',
          password: 'password',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(502)
      const data = await res.json()
      expect(data.error).toBe('Authentication service temporarily unavailable')
    })
  })
})
