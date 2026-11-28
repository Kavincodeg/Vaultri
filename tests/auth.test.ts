import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { authOptions } from '@/lib/auth'
import { extractClientIp, coreApiFetch } from '@/lib/core-api/server'
import { POST as signupPost } from '@/app/api/auth/signup/route'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { sanitizeNextUrl, handleRefreshAttempt } from '@/app/auth/refreshing/sanitize'
import { performLockedUpdate } from '@/components/SessionKeepAlive'
import { NextRequest } from 'next/server'
import fs from 'fs'
import path from 'path'

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
      NEXTAUTH_SECRET: 'test-nextauth-secret-min-32-chars-long',
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

  describe('2. New Refresh Behavior (Design 1 & 2)', () => {
    it('trigger undefined with an expired token -> zero refresh calls and the token unchanged', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        accessExpiresAt: now - 50, // Already expired
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res = await authOptions.callbacks?.jwt?.({ token, user: undefined, trigger: undefined })
      expect(fetchMock).not.toHaveBeenCalled()
      expect(res?.accessToken).toBe('access-1')
      expect(res?.refreshToken).toBe('refresh-1')
    })

    it('trigger "update" with a token 10 minutes from expiry -> zero calls and token unchanged', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'refresh-1',
        accessExpiresAt: now + 600, // 10 minutes left (> 300s)
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res = await authOptions.callbacks?.jwt?.({ token, user: undefined, trigger: 'update' })
      expect(fetchMock).not.toHaveBeenCalled()
      expect(res?.accessToken).toBe('access-1')
    })

    it('trigger "update" within 300 s -> exactly one call and new tokens stored', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        json: async () => ({
          accessToken: 'access-new',
          refreshToken: 'refresh-new',
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
        accessExpiresAt: now + 250, // Within 300s
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const res = await authOptions.callbacks?.jwt?.({ token, user: undefined, trigger: 'update' })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(res?.accessToken).toBe('access-new')
      expect(res?.refreshToken).toBe('refresh-new')
      expect(res?.error).toBe(false)
    })

    it('update with 401 -> error flag set and session callback reports no usable session', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 401,
        json: async () => ({ error: 'Invalid or revoked refresh token' }),
      })
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'access-1',
        refreshToken: 'revoked-token',
        accessExpiresAt: now + 100,
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const resToken = await authOptions.callbacks?.jwt?.({ token, user: undefined, trigger: 'update' })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(resToken?.error).toBe(true)

      // Session callback should report no usable session
      const session = await authOptions.callbacks?.session?.({
        session: { expires: new Date().toISOString() },
        token: resToken!,
        user: undefined as any,
        newSession: undefined,
        trigger: 'update',
      })
      expect(session?.user).toBeUndefined()
      expect((session as any)?.error).toBe(true)
    })

    it('update with 429/5xx/timeout -> token kept, session still usable while access token valid, no loop', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: false,
        status: 502,
        json: async () => ({ error: 'Service Unavailable' }),
      })
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-1',
        email: 'seller@example.com',
        name: 'Seller Name',
        accessToken: 'access-valid',
        refreshToken: 'refresh-valid',
        accessExpiresAt: now + 200, // Valid for 200 more seconds
        refreshExpiresAt: now + 600000,
      }

      // @ts-expect-error NextAuth callback signature
      const resToken = await authOptions.callbacks?.jwt?.({ token, user: undefined, trigger: 'update' })
      expect(fetchMock).toHaveBeenCalledTimes(1)
      // Token kept
      expect(resToken?.accessToken).toBe('access-valid')
      expect(resToken?.refreshToken).toBe('refresh-valid')
      expect(resToken?.error).toBe(false)
      expect(resToken?.transientError).toBe(true)

      // Session is still usable because access token is still valid
      const session = await authOptions.callbacks?.session?.({
        session: { expires: new Date().toISOString() },
        token: resToken!,
        user: undefined as any,
        newSession: undefined,
        trigger: 'update',
      })
      expect(session?.user).toBeDefined()
      expect((session?.user as any)?.sellerId).toBe('seller-1')
      expect((session as any)?.error).toBeUndefined()
    })
  })

  describe('3. Cookie Persistence Simulation', () => {
    it('simulates real cookie behavior: N server reads do not write cookie; only update writes cookie; no revoked token ever sent', async () => {
      // Model the cookie storage in the browser / client
      let cookieToken = {
        sub: 'seller-1',
        email: 'seller@example.com',
        accessToken: 'acc-1',
        refreshToken: 'ref-1',
        accessExpiresAt: Math.floor(Date.now() / 1000) + 100, // near expiry
        refreshExpiresAt: Math.floor(Date.now() / 1000) + 600000,
        error: false,
      }

      const activeRefreshTokensOnServer = new Set(['ref-1'])
      let serverRefreshCallCount = 0

      // Core-API refresh mock simulating single-use token rotation
      const fetchMock = vi.fn().mockImplementation(async (url: string, init: any) => {
        if (url.includes('/api/v1/auth/refresh')) {
          serverRefreshCallCount++
          const body = JSON.parse(init.body)
          if (!activeRefreshTokensOnServer.has(body.refreshToken)) {
            // Revoked token used!
            return {
              ok: false,
              status: 401,
              json: async () => ({ error: 'Refresh token revoked' }),
            }
          }
          // Invalidate old token and issue new one
          activeRefreshTokensOnServer.delete(body.refreshToken)
          const newRt = `ref-${serverRefreshCallCount + 1}`
          activeRefreshTokensOnServer.add(newRt)
          return {
            ok: true,
            status: 200,
            json: async () => ({
              accessToken: `acc-${serverRefreshCallCount + 1}`,
              refreshToken: newRt,
              expiresIn: 900,
              tokenType: 'Bearer',
            }),
          }
        }
        return { ok: true, status: 200, json: async () => ({}) }
      })
      global.fetch = fetchMock

      // 1. Run N consecutive server reads (e.g. getServerSession calls in RSC / Route Handlers)
      // On server reads, trigger is undefined and cookies cannot be written back (dummy setCookie)
      for (let i = 0; i < 5; i++) {
        const readResult = await authOptions.callbacks?.jwt?.({
          token: { ...cookieToken },
          user: undefined,
          trigger: undefined,
        } as any)
        // Crucial: Server reads must NOT have modified the cookie or called refresh!
        expect(fetchMock).not.toHaveBeenCalled()
        expect(readResult?.refreshToken).toBe(cookieToken.refreshToken)
      }

      // 2. Client initiates one update() call (which goes through NextAuth route handler that CAN set cookies)
      const updateResult = await authOptions.callbacks?.jwt?.({
        token: { ...cookieToken },
        user: undefined,
        trigger: 'update',
      } as any)
      expect(fetchMock).toHaveBeenCalledTimes(1)
      expect(updateResult?.refreshToken).toBe('ref-2')

      // Update writes the cookie!
      cookieToken = { ...cookieToken, ...updateResult } as any

      // 3. Run more server reads using the updated cookie
      for (let i = 0; i < 5; i++) {
        const postReadResult = await authOptions.callbacks?.jwt?.({
          token: { ...cookieToken },
          user: undefined,
          trigger: undefined,
        } as any)
        expect(fetchMock).toHaveBeenCalledTimes(1) // Still exactly 1 refresh call in total
        expect(postReadResult?.refreshToken).toBe('ref-2')
      }

      // Verify that the active refresh token was never revoked or mismatched
      expect(activeRefreshTokensOnServer.has('ref-2')).toBe(true)
      expect(serverRefreshCallCount).toBe(1)
    })
  })

  describe('4. Server-Side Helper getAccessTokenOrThrow', () => {
    it('returns access token when valid and at least 30s remaining', async () => {
      const now = Math.floor(Date.now() / 1000)
      const validToken = {
        accessToken: 'valid-access-token',
        accessExpiresAt: now + 120, // 120s left (> 30s)
      }

      const reqMock = {
        cookies: { 'next-auth.session-token': 'dummy' },
        headers: {},
      } as any

      const nextAuthJwt = await import('next-auth/jwt')
      vi.spyOn(nextAuthJwt, 'getToken').mockResolvedValue(validToken as any)

      const token = await getAccessTokenOrThrow(reqMock)
      expect(token).toBe('valid-access-token')
    })

    it('throws NeedsRefreshError and makes zero core-api calls when under 30s left or expired', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const now = Math.floor(Date.now() / 1000)
      const expiringToken = {
        accessToken: 'expiring-access-token',
        accessExpiresAt: now + 20, // 20s left (< 30s)
      }

      const reqMock = {
        cookies: { 'next-auth.session-token': 'dummy' },
        headers: {},
      } as any

      const nextAuthJwt = await import('next-auth/jwt')
      vi.spyOn(nextAuthJwt, 'getToken').mockResolvedValue(expiringToken as any)

      await expect(getAccessTokenOrThrow(reqMock)).rejects.toThrow(NeedsRefreshError)
      expect(fetchMock).not.toHaveBeenCalled()
    })
  })

  describe('5. Client Keep-Alive & Locks', () => {
    it('performLockedUpdate wraps update in navigator.locks.request with name "session-refresh"', async () => {
      const requestMock = vi.fn().mockImplementation(async (name: string, cb: () => Promise<any>) => {
        expect(name).toBe('session-refresh')
        return await cb()
      })

      Object.defineProperty(global, 'navigator', {
        value: {
          locks: {
            request: requestMock,
          },
        },
        configurable: true,
        writable: true,
      })

      const updateMock = vi.fn().mockResolvedValue({ status: 'ok' })
      const res = await performLockedUpdate(updateMock)

      expect(requestMock).toHaveBeenCalledTimes(1)
      expect(updateMock).toHaveBeenCalledTimes(1)
      expect(res).toEqual({ status: 'ok' })
    })

    it('performLockedUpdate uses fallback path when navigator.locks is missing', async () => {
      Object.defineProperty(global, 'navigator', {
        value: {},
        configurable: true,
        writable: true,
      })

      const updateMock = vi.fn().mockResolvedValue({ status: 'fallback-ok' })
      const res = await performLockedUpdate(updateMock)

      expect(updateMock).toHaveBeenCalledTimes(1)
      expect(res).toEqual({ status: 'fallback-ok' })
    })

    it('asserts SessionProvider props: session prop passed, refetchInterval 0, refetchOnWindowFocus false', () => {
      const providersSource = fs.readFileSync(
        path.resolve(__dirname, '../components/Providers.tsx'),
        'utf8'
      )
      expect(providersSource).toMatch(/session=\{session\}/)
      expect(providersSource).toMatch(/refetchInterval=\{0\}/)
      expect(providersSource).toMatch(/refetchOnWindowFocus=\{false\}/)
      expect(providersSource).toMatch(/<SessionKeepAlive\s*\/>/)
    })

    it('verifies that no client file calls getSession() or triggers a session refetch', () => {
      const appDir = path.resolve(__dirname, '../app')
      const componentsDir = path.resolve(__dirname, '../components')

      const checkFiles = (dir: string) => {
        if (!fs.existsSync(dir)) return
        const files = fs.readdirSync(dir)
        for (const file of files) {
          const fullPath = path.join(dir, file)
          const stat = fs.statSync(fullPath)
          if (stat.isDirectory()) {
            checkFiles(fullPath)
          } else if (file.endsWith('.ts') || file.endsWith('.tsx')) {
            const content = fs.readFileSync(fullPath, 'utf8')
            expect(content).not.toMatch(/\bgetSession\s*\(/)
          }
        }
      }

      checkFiles(appDir)
      checkFiles(componentsDir)
    })
  })

  describe('6. /auth/refreshing URL Sanitizer & Security Fixes', () => {
    it('rejects //evil.com, https://evil.com, and /\\evil, and accepts /dashboard/deals?x=1', () => {
      expect(sanitizeNextUrl('//evil.com')).toBe('/dashboard')
      expect(sanitizeNextUrl('https://evil.com')).toBe('/dashboard')
      expect(sanitizeNextUrl('/\\evil')).toBe('/dashboard')
      expect(sanitizeNextUrl('/dashboard/deals?x=1')).toBe('/dashboard/deals?x=1')
      expect(sanitizeNextUrl(null)).toBe('/dashboard')
      expect(sanitizeNextUrl(undefined)).toBe('/dashboard')
      expect(sanitizeNextUrl('')).toBe('/dashboard')
    })

    it('security fix: rejects URL-encoded bypasses, protocol injections, and self-redirect loops', () => {
      // URL-encoded backslash and protocol-relative
      expect(sanitizeNextUrl('/%5cevil.com')).toBe('/dashboard')
      expect(sanitizeNextUrl('/%5Cevil.com')).toBe('/dashboard')
      expect(sanitizeNextUrl('/%2f%2fevil.com')).toBe('/dashboard')
      expect(sanitizeNextUrl('/%2fevil.com/%2f')).toBe('/dashboard')
      // Direct protocol schemes
      expect(sanitizeNextUrl('javascript:alert(1)')).toBe('/dashboard')
      expect(sanitizeNextUrl('data:text/html,evil')).toBe('/dashboard')
      // Redirect loops back to auth endpoints
      expect(sanitizeNextUrl('/auth/refreshing')).toBe('/dashboard')
      expect(sanitizeNextUrl('/auth/refreshing?next=/dashboard')).toBe('/dashboard')
      expect(sanitizeNextUrl('/auth/signin')).toBe('/dashboard')
    })
  })

  describe('7. Session Callback Token Safety', () => {
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

  describe('8. Authorize Credentials', () => {
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

  describe('9. Sign Out Event', () => {
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

  describe('10. Signup Proxy Route', () => {
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

    it('returns 400 with {error: "Invalid signup data"} and does not call core-api on malformed JSON', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{"invalid-json":',
      })

      const res = await signupPost(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toBe('Invalid signup data')
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('returns 400 with {error: "Invalid signup data"} and does not call core-api on non-string password', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Seller',
          email: 'seller@example.com',
          password: 12345678,
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toBe('Invalid signup data')
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('returns 400 with {error: "Invalid signup data"} and does not call core-api on oversized password (> 128 chars)', async () => {
      const fetchMock = vi.fn()
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'Seller',
          email: 'seller@example.com',
          password: 'a'.repeat(129),
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toBe('Invalid signup data')
      expect(fetchMock).not.toHaveBeenCalled()
    })

    it('returns 201 with body containing exactly the keys id, email, name (drops phone, plan, createdAt)', async () => {
      const fetchMock = vi.fn().mockResolvedValue({
        ok: true,
        status: 201,
        json: async () => ({
          id: 'new-seller-uuid',
          email: 'new@seller.com',
          name: 'New Seller',
          phone: '+919876543210',
          plan: 'PRO',
          createdAt: '2026-11-27T12:00:00Z',
        }),
      })
      global.fetch = fetchMock

      const req = new NextRequest('http://localhost:3000/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: 'New Seller',
          email: 'new@seller.com',
          password: 'secure-password',
          phone: '+919876543210',
        }),
      })

      const res = await signupPost(req)
      expect(res.status).toBe(201)
      const data = await res.json()
      expect(Object.keys(data).sort()).toEqual(['email', 'id', 'name'])
      expect(data).toEqual({
        id: 'new-seller-uuid',
        email: 'new@seller.com',
        name: 'New Seller',
      })
      expect((data as any).phone).toBeUndefined()
      expect((data as any).plan).toBeUndefined()
      expect((data as any).createdAt).toBeUndefined()
    })
  })

  describe('10. Regression: JWT Callback Session Argument Injection', () => {
    it('ignores session argument on trigger "update" and prevents value injection into token', async () => {
      const now = Math.floor(Date.now() / 1000)
      const token = {
        sub: 'seller-orig-id',
        email: 'seller@example.com',
        accessToken: 'orig-access-token',
        refreshToken: 'orig-refresh-token',
        accessExpiresAt: now + 500, // not near expiry, so no refresh call is made
        refreshExpiresAt: now + 600000,
        error: true,
      }

      // Invoke jwt callback with trigger 'update' and third argument session containing injected fields
      const res = await authOptions.callbacks?.jwt?.({
        token: { ...token },
        user: undefined,
        trigger: 'update',
        session: {
          accessToken: 'x',
          refreshToken: 'y',
          sellerId: 'someone-else',
          error: false,
        },
      } as any)

      // Assert none of those values reach the returned token
      expect(res?.accessToken).not.toBe('x')
      expect(res?.refreshToken).not.toBe('y')
      expect((res as any)?.sellerId).toBeUndefined()
      expect(res?.sub).not.toBe('someone-else')
      expect(res?.error).not.toBe(false)

      // Assert original token values are preserved
      expect(res?.accessToken).toBe('orig-access-token')
      expect(res?.refreshToken).toBe('orig-refresh-token')
      expect(res?.sub).toBe('seller-orig-id')
      expect(res?.error).toBe(true)
    })
  })

  describe('11. Client Dead-Session Path: /auth/refreshing', () => {
    it('when update resolves with {user: undefined, expires, error: true}, makes exactly one attempt and signs out once without looping', async () => {
      const updateMock = vi.fn().mockResolvedValue({
        user: undefined,
        expires: '2026-11-28T12:00:00.000Z',
        error: true,
      })
      const signOutMock = vi.fn().mockResolvedValue(undefined)
      const routerReplaceMock = vi.fn()

      const result = await handleRefreshAttempt({
        update: updateMock,
        target: '/dashboard',
        signOut: signOutMock,
        routerReplace: routerReplaceMock,
      })

      // Exactly one update attempt made
      expect(updateMock).toHaveBeenCalledTimes(1)
      // Signs out once to /auth/signin
      expect(signOutMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).toHaveBeenCalledWith({ callbackUrl: '/auth/signin' })
      // Does not redirect to target
      expect(routerReplaceMock).not.toHaveBeenCalled()
      expect(result.status).toBe('unauthenticated')
    })

    it('when update resolves with missing user (no user field), signs out once without looping', async () => {
      const updateMock = vi.fn().mockResolvedValue({
        expires: '2026-11-28T12:00:00.000Z',
      })
      const signOutMock = vi.fn().mockResolvedValue(undefined)
      const routerReplaceMock = vi.fn()

      const result = await handleRefreshAttempt({
        update: updateMock,
        target: '/dashboard/deals',
        signOut: signOutMock,
        routerReplace: routerReplaceMock,
      })

      expect(updateMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).toHaveBeenCalledWith({ callbackUrl: '/auth/signin' })
      expect(routerReplaceMock).not.toHaveBeenCalled()
      expect(result.status).toBe('unauthenticated')
    })

    it('when update rejects (e.g. network crash), signs out once without looping', async () => {
      const updateMock = vi.fn().mockRejectedValue(new Error('Network failure'))
      const signOutMock = vi.fn().mockResolvedValue(undefined)
      const routerReplaceMock = vi.fn()

      const result = await handleRefreshAttempt({
        update: updateMock,
        target: '/dashboard',
        signOut: signOutMock,
        routerReplace: routerReplaceMock,
      })

      expect(updateMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).toHaveBeenCalledWith({ callbackUrl: '/auth/signin' })
      expect(routerReplaceMock).not.toHaveBeenCalled()
      expect(result.status).toBe('unauthenticated')
    })

    it('when update succeeds with valid user, redirects to target and does not sign out', async () => {
      const updateMock = vi.fn().mockResolvedValue({
        user: { name: 'Seller User', email: 'seller@example.com' },
        expires: '2026-11-28T12:00:00.000Z',
      })
      const signOutMock = vi.fn().mockResolvedValue(undefined)
      const routerReplaceMock = vi.fn()

      const result = await handleRefreshAttempt({
        update: updateMock,
        target: '/dashboard/settings',
        signOut: signOutMock,
        routerReplace: routerReplaceMock,
      })

      expect(updateMock).toHaveBeenCalledTimes(1)
      expect(signOutMock).not.toHaveBeenCalled()
      expect(routerReplaceMock).toHaveBeenCalledTimes(1)
      expect(routerReplaceMock).toHaveBeenCalledWith('/dashboard/settings')
      expect(result.status).toBe('authenticated')
    })
  })
})
