'use client'

import React, { Suspense, useEffect, useRef } from 'react'
import { useRouter, useSearchParams } from 'next/navigation'
import { useSession, signOut } from 'next-auth/react'
import { performLockedUpdate } from '@/components/SessionKeepAlive'
import { sanitizeNextUrl, handleRefreshAttempt } from './sanitize'

function RefreshingContent() {
  const router = useRouter()
  const searchParams = useSearchParams()
  const { update } = useSession()
  const hasRefreshedRef = useRef(false)
  const updateRef = useRef(update)
  updateRef.current = update

  useEffect(() => {
    // Loop guard: at most one refresh attempt per visit
    if (hasRefreshedRef.current) return
    hasRefreshedRef.current = true

    const nextParam = searchParams.get('next')
    const target = sanitizeNextUrl(nextParam)

    handleRefreshAttempt({
      update: () => performLockedUpdate(() => updateRef.current({})),
      target,
      signOut,
      routerReplace: (url) => router.replace(url),
    })
  }, [router, searchParams])

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div className="card" style={{ textAlign: 'center', maxWidth: '360px', padding: '32px' }}>
        <div style={{ fontSize: '2rem', marginBottom: '16px' }}>⏳</div>
        <h2 style={{ fontSize: '1.25rem', fontWeight: 600, marginBottom: '8px' }}>Refreshing Session</h2>
        <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Securing your session, please wait...</p>
      </div>
    </div>
  )
}

export default function RefreshingPage() {
  return (
    <Suspense
      fallback={
        <div style={{ minHeight: 'calc(100vh - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
          <div className="card" style={{ textAlign: 'center', maxWidth: '360px', padding: '32px' }}>
            <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Loading...</p>
          </div>
        </div>
      }
    >
      <RefreshingContent />
    </Suspense>
  )
}
