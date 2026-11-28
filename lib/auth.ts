import { NextAuthOptions, User } from 'next-auth'
import { JWT } from 'next-auth/jwt'
import CredentialsProvider from 'next-auth/providers/credentials'
import {
  coreApiFetch,
  extractClientIp,
  RateLimitedError,
  UnauthorizedError,
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
    async jwt({ token, user, trigger }) {
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
        token.transientError = false
        return token
      }

      // Design 1: Refresh ONLY when trigger === 'update' (client-initiated NextAuth route handler call)
      // AND access token is within 300 seconds of expiry or already expired.
      // On every other call (getServerSession, GET /api/auth/session, middleware, trigger undefined),
      // NEVER call core-api refresh and return token unchanged.
      if (trigger === 'update') {
        if (token.error) return token

        const accessExpiresAt = (token.accessExpiresAt as number) || 0
        const isNearExpiry = now >= accessExpiresAt - 300
        const refreshToken = token.refreshToken as string | undefined

        if (isNearExpiry && refreshToken) {
          try {
            const res = await coreApiFetch<CoreLoginResponse>({
              method: 'POST',
              path: '/api/v1/auth/refresh',
              body: { refreshToken },
            })
            token.accessToken = res.accessToken
            token.refreshToken = res.refreshToken
            token.accessExpiresAt = now + (res.expiresIn || 900)
            token.refreshExpiresAt = now + 7 * 24 * 60 * 60
            token.error = false
            token.transientError = false
            return token
          } catch (err) {
            if (err instanceof UnauthorizedError) {
              // 401 from core-api: the refresh token is dead; set error flag so session callback reports no usable session
              token.error = true
              token.transientError = false
              return token
            }
            // 429, 5xx, timeout: keep the token, set transient flag, do not end session (client retries later), never loop
            token.error = false
            token.transientError = true
            return token
          }
        }
      }

      return token
    },

    async session({ session, token }) {
      // If refresh token is dead (401 from core-api), report no usable session
      if (token.error) {
        return {
          user: undefined,
          expires: session.expires,
          error: true,
        } as unknown as typeof session
      }

      // Session must contain ONLY sellerId, email, name, and optional error flag.
      // No tokens at any depth of the session object.
      return {
        user: {
          sellerId: token.sub,
          email: token.email,
          name: token.name,
        },
        expires: session.expires,
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
