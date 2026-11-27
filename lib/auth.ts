import { NextAuthOptions, User } from 'next-auth'
import { JWT } from 'next-auth/jwt'
import CredentialsProvider from 'next-auth/providers/credentials'
import {
  coreApiFetch,
  extractClientIp,
  RateLimitedError,
  UnauthorizedError,
  UnavailableError,
} from '@/lib/core-api/server'

interface CoreLoginResponse {
  accessToken: string
  refreshToken: string
  expiresIn: number
  tokenType: string
}

interface CoreMeResponse {
  id: string
  email: string
  name: string
  phone?: string | null
  plan?: string
}

interface RefreshResult {
  accessToken: string
  refreshToken: string
  accessExpiresAt: number
  refreshExpiresAt: number
}

// In-flight refresh promises map for deduplication within the same server process
const inFlightRefreshes = new Map<string, Promise<RefreshResult>>()

// Track last refresh failure time per refresh token to enforce 30s cooldown before retrying
const refreshCooldowns = new Map<string, number>()

/**
 * Performs token rotation via core-api POST /auth/refresh with single in-flight deduplication.
 */
export async function refreshAccessToken(refreshToken: string): Promise<RefreshResult> {
  const existing = inFlightRefreshes.get(refreshToken)
  if (existing) {
    return existing
  }

  const promise = (async () => {
    try {
      const res = await coreApiFetch<CoreLoginResponse>({
        method: 'POST',
        path: '/api/v1/auth/refresh',
        body: { refreshToken },
      })
      const now = Math.floor(Date.now() / 1000)
      const result: RefreshResult = {
        accessToken: res.accessToken,
        refreshToken: res.refreshToken,
        accessExpiresAt: now + (res.expiresIn || 900),
        refreshExpiresAt: now + 7 * 24 * 60 * 60,
      }
      refreshCooldowns.delete(refreshToken)
      return result
    } catch (err) {
      refreshCooldowns.set(refreshToken, Date.now())
      throw err
    } finally {
      inFlightRefreshes.delete(refreshToken)
    }
  })()

  inFlightRefreshes.set(refreshToken, promise)
  return promise
}

export const authOptions: NextAuthOptions = {
  session: {
    strategy: 'jwt',
    // 7 days minus 1 hour margin = 601,200 seconds
    maxAge: 7 * 24 * 60 * 60 - 3600,
  },
  jwt: {
    maxAge: 7 * 24 * 60 * 60 - 3600,
  },
  providers: [
    CredentialsProvider({
      name: 'Credentials',
      credentials: {
        email: { label: 'Email', type: 'email' },
        password: { label: 'Password', type: 'password' },
      },
      async authorize(credentials, req) {
        if (!credentials?.email || !credentials?.password) return null

        const clientIp = extractClientIp(req?.headers)

        let loginRes: CoreLoginResponse
        try {
          loginRes = await coreApiFetch<CoreLoginResponse>({
            method: 'POST',
            path: '/api/v1/auth/login',
            body: {
              email: credentials.email,
              password: credentials.password,
            },
            clientIp,
          })
        } catch (err) {
          if (err instanceof UnauthorizedError) {
            return null
          }
          if (err instanceof RateLimitedError) {
            throw new Error('TOO_MANY_ATTEMPTS')
          }
          throw new Error('SERVICE_UNAVAILABLE')
        }

        try {
          const me = await coreApiFetch<CoreMeResponse>({
            method: 'GET',
            path: '/api/v1/me',
            token: loginRes.accessToken,
            clientIp,
          })

          const now = Math.floor(Date.now() / 1000)
          return {
            id: me.id,
            email: me.email,
            name: me.name,
            accessToken: loginRes.accessToken,
            refreshToken: loginRes.refreshToken,
            accessExpiresAt: now + (loginRes.expiresIn || 900),
            refreshExpiresAt: now + 7 * 24 * 60 * 60,
          } as User & {
            accessToken: string
            refreshToken: string
            accessExpiresAt: number
            refreshExpiresAt: number
          }
        } catch (err) {
          throw new Error('SERVICE_UNAVAILABLE')
        }
      },
    }),
  ],
  callbacks: {
    async jwt({ token, user }) {
      const now = Math.floor(Date.now() / 1000)

      // Initial sign-in
      if (user) {
        const u = user as unknown as {
          id: string
          email: string
          name: string
          accessToken: string
          refreshToken: string
          accessExpiresAt: number
          refreshExpiresAt: number
        }
        token.sub = u.id
        token.email = u.email
        token.name = u.name
        token.accessToken = u.accessToken
        token.refreshToken = u.refreshToken
        token.accessExpiresAt = u.accessExpiresAt
        token.refreshExpiresAt = u.refreshExpiresAt
        token.error = false
        return token
      }

      // Subsequent reads: Proactive refresh within 60s of expiry
      const accessExpiresAt = (token.accessExpiresAt as number) || 0
      const isNearExpiry = now >= accessExpiresAt - 60
      const refreshToken = token.refreshToken as string | undefined

      if (!isNearExpiry || !refreshToken) {
        return token
      }

      // Check if this refresh token recently failed (30s cooldown)
      const lastFailed = refreshCooldowns.get(refreshToken)
      if (lastFailed && Date.now() - lastFailed < 30000) {
        token.error = true
        return token
      }

      try {
        const refreshed = await refreshAccessToken(refreshToken)
        token.accessToken = refreshed.accessToken
        token.refreshToken = refreshed.refreshToken
        token.accessExpiresAt = refreshed.accessExpiresAt
        token.refreshExpiresAt = refreshed.refreshExpiresAt
        token.error = false
        return token
      } catch (err) {
        // Soft refresh failure: keep existing token, set error flag
        token.error = true
        return token
      }
    },

    async session({ session, token }) {
      const now = Math.floor(Date.now() / 1000)
      const accessExpiresAt = (token.accessExpiresAt as number) || 0

      // If access token is expired AND refresh has failed, session is no longer usable
      if (token.error && now >= accessExpiresAt) {
        return {
          user: undefined,
          expires: session.expires,
          error: true,
        } as unknown as typeof session
      }

      return {
        user: {
          sellerId: token.sub,
          email: token.email,
          name: token.name,
        },
        expires: session.expires,
        ...(token.error ? { error: true } : {}),
      } as typeof session
    },
  },
  events: {
    async signOut({ token }) {
      const refreshToken = (token as JWT)?.refreshToken as string | undefined
      if (!refreshToken) return

      const controller = new AbortController()
      const timeoutId = setTimeout(() => controller.abort(), 2000)

      try {
        const baseUrl = process.env.CORE_API_URL
        if (!baseUrl) return

        await fetch(`${baseUrl.replace(/\/+$/, '')}/api/v1/auth/logout`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({ refreshToken }),
          signal: controller.signal,
        })
      } catch {
        // Best-effort logout; ignore and swallow failures
      } finally {
        clearTimeout(timeoutId)
      }
    },
  },
  pages: { signIn: '/auth/signin' },
  secret: process.env.NEXTAUTH_SECRET,
}
