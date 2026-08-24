/**
 * Next.js Instrumentation — Sentry server + edge init.
 * Called once when the Next.js server starts.
 * Doc ref: Section 9 — Sentry for errors + alerts on failed webhooks / reminders / agent errors.
 */
export async function register() {
  if (process.env.NEXT_RUNTIME === 'nodejs') {
    const Sentry = await import('@sentry/nextjs')
    Sentry.init({
      dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
      enabled: process.env.NODE_ENV === 'production',
      // Capture all server-side traces at low volume initially
      tracesSampleRate: 1.0,
      debug: false,
    })
  }

  if (process.env.NEXT_RUNTIME === 'edge') {
    const Sentry = await import('@sentry/nextjs')
    Sentry.init({
      dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
      enabled: process.env.NODE_ENV === 'production',
      tracesSampleRate: 1.0,
    })
  }
}
