'use client'
/**
 * Global error boundary — captures React rendering errors in the App Router
 * and reports them to Sentry.
 * Doc ref: Section 9 — Sentry for all unhandled errors.
 */
import * as Sentry from '@sentry/nextjs'
import { useEffect } from 'react'

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string }
  reset: () => void
}) {
  useEffect(() => {
    Sentry.captureException(error)
  }, [error])

  return (
    <html lang='en'>
      <body style={{ background: '#0d0f14', color: '#e2e8f0', fontFamily: 'system-ui, sans-serif', display: 'flex', alignItems: 'center', justifyContent: 'center', minHeight: '100vh', margin: 0 }}>
        <div style={{ textAlign: 'center', maxWidth: '480px', padding: '32px' }}>
          <div style={{ fontSize: '2.5rem', marginBottom: '16px' }}>⚠️</div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '12px' }}>Something went wrong</h1>
          <p style={{ color: '#94a3b8', marginBottom: '24px', lineHeight: 1.6 }}>
            An unexpected error occurred. The team has been notified.
          </p>
          <button
            onClick={reset}
            style={{ background: '#6366f1', color: '#fff', border: 'none', borderRadius: '8px', padding: '12px 24px', fontSize: '0.9rem', fontWeight: 600, cursor: 'pointer' }}
          >
            Try again
          </button>
        </div>
      </body>
    </html>
  )
}
