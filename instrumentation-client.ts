/**
 * Next.js Client Instrumentation — Sentry browser init.
 * Doc ref: Section 9 — Sentry for client-side errors.
 */
import * as Sentry from '@sentry/nextjs'

Sentry.init({
  dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
  enabled: process.env.NODE_ENV === 'production',
  // 10% of sessions for performance tracing
  tracesSampleRate: 0.1,
  // Replay 10% of sessions, 100% of sessions with errors
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0,
})

// Required for Sentry to capture navigation transitions in App Router
export const onRouterTransitionStart = Sentry.captureRouterTransitionStart
