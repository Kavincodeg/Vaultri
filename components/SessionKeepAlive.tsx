'use client'

import { useEffect, useRef } from 'react'
import { useSession } from 'next-auth/react'

/**
 * Executes an update callback inside Web Locks API with lock name 'session-refresh'
 * so only one tab refreshes at a time.
 * Fallback when navigator.locks is missing: random delay of 0 to 3 seconds before calling update({}).
 */
export async function performLockedUpdate(updateFn: () => Promise<any>): Promise<any> {
  if (typeof navigator !== 'undefined' && 'locks' in navigator && navigator.locks?.request) {
    return await navigator.locks.request('session-refresh', async () => {
      return await updateFn()
    })
  }

  // Fallback when navigator.locks is missing: random delay of 0 to 3 seconds
  const delay = Math.random() * 3000
  await new Promise((resolve) => setTimeout(resolve, delay))
  return await updateFn()
}

/**
 * Client keep-alive component rendered once in the root layout inside SessionProvider.
 *
 * NOTE: update({}) in this component is the ONLY client call that touches the session.
 * No other client file calls getSession or triggers a session refetch.
 *
 * Cadence Stability & Bug 2 Mitigation:
 * NextAuth's SessionProvider memoizes `value` with dependencies `[session, loading]`.
 * Whenever `update({})` runs, `status` flips to "loading" and back, recreating the `update`
 * function instance. To prevent resetting the 240 s cadence on every update:
 * 1. `status` and `update` are kept in refs (`statusRef`, `updateRef`).
 * 2. The initial mount/authentication trigger runs in a separate effect depending on `[status]`
 *    guarded by `hasTriggeredInitialRef`.
 * 3. The 240 s interval and visibilitychange listener run once with an empty dependency array `[]`,
 *    reading `statusRef.current` inside handlers, preserving uninterrupted cadence.
 */
export function SessionKeepAlive() {
  const { data: session, status, update } = useSession()
  const updateRef = useRef(update)
  updateRef.current = update
  const statusRef = useRef(status)
  statusRef.current = status
  const sessionRef = useRef(session)
  sessionRef.current = session
  const hasTriggeredInitialRef = useRef(false)

  // Separate effect: fires once when status first becomes 'authenticated' with a usable session
  useEffect(() => {
    const isUsable = status === 'authenticated' && !!session?.user && !(session as any)?.error
    if (isUsable && !hasTriggeredInitialRef.current) {
      hasTriggeredInitialRef.current = true
      performLockedUpdate(() => updateRef.current({})).catch(() => {})
    }
  }, [status, session])

  // Keep-alive effect: runs once on mount with empty dependency array []
  // Reading statusRef.current and sessionRef.current inside handlers ensures status flips do not reset the 240 s cadence.
  // Treats dead sessions ("no session.user or session.error === true") as unusable and skips updates.
  useEffect(() => {
    let isUpdating = false
    const triggerUpdate = async () => {
      if (isUpdating) return
      const isUsable = statusRef.current === 'authenticated' && !!sessionRef.current?.user && !(sessionRef.current as any)?.error
      if (!isUsable) return
      isUpdating = true
      try {
        await performLockedUpdate(() => updateRef.current({}))
      } catch {
        // Best-effort keep-alive; ignore errors so background failures don't crash UI
      } finally {
        isUpdating = false
      }
    }

    // (b) Every 240 seconds
    const intervalId = setInterval(() => {
      triggerUpdate()
    }, 240000)

    // (c) When the tab becomes visible after being hidden
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        triggerUpdate()
      }
    }
    document.addEventListener('visibilitychange', handleVisibilityChange)

    return () => {
      clearInterval(intervalId)
      document.removeEventListener('visibilitychange', handleVisibilityChange)
    }
  }, [])

  return null
}
