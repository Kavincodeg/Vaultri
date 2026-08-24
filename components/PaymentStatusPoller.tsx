'use client'
import { useEffect, useRef } from 'react'
import { useRouter } from 'next/navigation'

interface Props {
  dealId: string
  /** Current deposit status from server render — polling stops if already 'completed' */
  initialDepositStatus: string | null
  /** Current deal status from server render */
  initialDealStatus: string
}

/**
 * Silently polls /api/deals/[id]/status every 4 seconds while a deposit is pending.
 * When the webhook flips the status to 'completed', the router is refreshed
 * so the server component re-renders with fresh DB data — no manual page reload needed.
 */
export function PaymentStatusPoller({ dealId, initialDepositStatus, initialDealStatus }: Props) {
  const router = useRouter()
  const intervalRef = useRef<ReturnType<typeof setInterval> | null>(null)

  // Only poll when deposit is not yet completed
  const shouldPoll =
    initialDepositStatus !== 'completed' && initialDealStatus === 'deposit_pending'

  useEffect(() => {
    if (!shouldPoll) return

    intervalRef.current = setInterval(async () => {
      try {
        const res = await fetch(`/api/deals/${dealId}/status`, { cache: 'no-store' })
        if (!res.ok) return
        const data = await res.json()

        // Webhook has landed — refresh the page so the server component re-runs
        if (data.depositStatus === 'completed' || data.dealStatus === 'deposit_paid') {
          if (intervalRef.current) clearInterval(intervalRef.current)
          router.refresh()
        }
      } catch {
        // Network error — silently retry next tick
      }
    }, 4000) // poll every 4 seconds

    return () => {
      if (intervalRef.current) clearInterval(intervalRef.current)
    }
  }, [dealId, shouldPoll, router])

  // Renders nothing — purely a background polling effect
  return null
}
