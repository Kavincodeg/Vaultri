'use client'
import { useState } from 'react'
import { useRouter } from 'next/navigation'

interface ReminderInfo {
  id: string
  status: string   // scheduled | sent | failed
  sentAt: string | null
}

interface Props {
  dealId: string
  status: string
  totalPrice: number
  customerName: string
  description: string
  pendingReminderIds: string[]
  reminders: ReminderInfo[]
}

export function DealActions({
  dealId,
  status,
  totalPrice,
  customerName,
  description,
  pendingReminderIds,
  reminders,
}: Props) {
  const router = useRouter()
  const [loading, setLoading] = useState<string | null>(null)
  const [message, setMessage] = useState<{ type: 'success' | 'error'; text: string } | null>(null)
  const [cancResult, setCancResult] = useState<{
    shortUrl: string
    draftMessage: string
    feeAmount: number
  } | null>(null)
  const [completed, setCompleted] = useState(status === 'completed')

  async function triggerAction(action: string, body: object) {
    setLoading(action)
    setMessage(null)
    try {
      const res = await fetch('/api/' + action, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error ?? 'Request failed')
      return data
    } catch (err: any) {
      setMessage({ type: 'error', text: err.message })
      return null
    } finally {
      setLoading(null)
    }
  }

  async function handleComplete() {
    const result = await triggerAction('deals/' + dealId + '/complete', {})
    if (result) {
      setCompleted(true)
      setMessage({ type: 'success', text: 'Deal marked as completed.' })
      router.refresh()
    }
  }

  async function handleCancellation() {
    const result = await triggerAction('cancellation', { dealId, customerName, totalPrice, description })
    if (result) {
      setCancResult(result)
      setMessage({ type: 'success', text: 'Cancellation fee link generated.' })
      router.refresh()
    }
  }

  async function handleSendReminder(reminderId: string) {
    const result = await triggerAction('reminders', { reminderId })
    if (result) {
      setMessage({ type: 'success', text: 'Reminder sent successfully.' })
      router.refresh()
    }
  }

  const isCancelled = status === 'cancelled'
  const isCompleted = completed
  const canComplete = !isCompleted && !isCancelled && (status === 'deposit_paid' || status === 'in_progress')
  const canCancel = !isCancelled && !isCompleted && status !== 'draft'

  // Reminder status display helpers
  const failedReminders = reminders.filter((r) => r.status === 'failed')

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>

      {/* ── Mark as Completed ─────────────────────────── */}
      {canComplete && (
        <div className='card' style={{ border: '1px solid rgba(34,197,94,0.3)' }}>
          <div style={{ fontWeight: 600, marginBottom: '10px', fontSize: '0.9rem', color: 'var(--success)' }}>
            ✅ Mark as Completed
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '12px', lineHeight: '1.5' }}>
            Order delivered and payment settled? Mark this deal as completed to close it out.
          </p>
          <button
            id='btn-mark-completed'
            className='btn btn-secondary'
            style={{ width: '100%', justifyContent: 'center', borderColor: 'rgba(34,197,94,0.4)', color: 'var(--success)' }}
            onClick={handleComplete}
            disabled={loading === 'deals/' + dealId + '/complete'}
          >
            {loading === 'deals/' + dealId + '/complete'
              ? <><span className='spinner' />&nbsp;Saving...</>
              : '✅ Mark Deal as Completed'}
          </button>
        </div>
      )}

      {/* ── Completed state ───────────────────────────── */}
      {isCompleted && (
        <div className='card' style={{ border: '1px solid rgba(34,197,94,0.3)' }}>
          <div style={{ fontWeight: 600, fontSize: '0.9rem', color: 'var(--success)' }}>
            ✅ Deal Completed
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginTop: '6px' }}>
            This deal has been marked as completed.
          </p>
        </div>
      )}

      {/* ── Reminder status ───────────────────────────── */}
      {reminders.length > 0 && (
        <div className='card'>
          <div style={{ fontWeight: 600, marginBottom: '10px', fontSize: '0.9rem' }}>🔔 Reminder Status</div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {reminders.map((r) => (
              <div key={r.id} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
                  {r.status === 'sent'
                    ? '📧 Sent ' + (r.sentAt ? new Date(r.sentAt).toLocaleDateString('en-IN') : '')
                    : r.status === 'failed'
                    ? '❌ Failed to send'
                    : '⏳ Scheduled'}
                </span>
                <span
                  className={
                    'badge ' +
                    (r.status === 'sent'
                      ? 'badge-paid'
                      : r.status === 'failed'
                      ? 'badge-cancelled'
                      : 'badge-pending')
                  }
                >
                  {r.status}
                </span>
              </div>
            ))}
          </div>

          {/* Manual send button for pending reminders */}
          {pendingReminderIds.length > 0 && (
            <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
              <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '10px', lineHeight: '1.5' }}>
                Manually trigger now — seller will receive an email.
              </p>
              <button
                id='btn-send-reminder'
                className='btn btn-secondary'
                style={{ width: '100%', justifyContent: 'center' }}
                onClick={() => handleSendReminder(pendingReminderIds[0])}
                disabled={loading === 'reminders'}
              >
                {loading === 'reminders'
                  ? <><span className='spinner' />&nbsp;Sending...</>
                  : '🔔 Send Reminder Now'}
              </button>
            </div>
          )}

          {/* Retry button for failed reminders */}
          {failedReminders.length > 0 && pendingReminderIds.length === 0 && (
            <div style={{ marginTop: '12px', paddingTop: '12px', borderTop: '1px solid var(--border)' }}>
              <button
                id='btn-retry-reminder'
                className='btn btn-secondary'
                style={{ width: '100%', justifyContent: 'center' }}
                onClick={() => handleSendReminder(failedReminders[0].id)}
                disabled={loading === 'reminders'}
              >
                {loading === 'reminders'
                  ? <><span className='spinner' />&nbsp;Retrying...</>
                  : '🔁 Retry Failed Reminder'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* ── Cancellation flow ─────────────────────────── */}
      {canCancel && !cancResult && (
        <div className='card' style={{ border: '1px solid rgba(239,68,68,0.3)' }}>
          <div style={{ fontWeight: 600, marginBottom: '10px', fontSize: '0.9rem', color: 'var(--danger)' }}>
            ⚠️ Customer Cancelled?
          </div>
          <p style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '12px', lineHeight: '1.5' }}>
            If the customer cancels after work has started, the AI agent will generate a cancellation fee
            link and a polite message for you to send them.
          </p>
          <button
            id='btn-trigger-cancellation'
            className='btn btn-danger'
            style={{ width: '100%', justifyContent: 'center' }}
            onClick={handleCancellation}
            disabled={loading === 'cancellation'}
          >
            {loading === 'cancellation'
              ? <><span className='spinner' />&nbsp;AI generating fee link...</>
              : '🛑 Generate Cancellation Fee'}
          </button>
        </div>
      )}

      {/* ── Cancellation result ───────────────────────── */}
      {cancResult && (
        <div className='card' style={{ border: '1px solid rgba(239,68,68,0.3)' }}>
          <div style={{ fontWeight: 600, marginBottom: '10px', fontSize: '0.9rem', color: 'var(--danger)' }}>
            🛑 Cancellation Fee Generated
          </div>
          <div className='link-box' style={{ marginBottom: '12px' }}>
            <span className='link-box-url'>{cancResult.shortUrl}</span>
            <a
              href={cancResult.shortUrl}
              target='_blank'
              rel='noopener noreferrer'
              className='btn btn-danger btn-sm'
              id='btn-canc-link'
            >
              Open ↗
            </a>
          </div>
          {cancResult.draftMessage && (
            <div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '6px', textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                Draft message for customer
              </div>
              <div style={{ background: 'var(--surface2)', border: '1px solid var(--border)', borderRadius: '8px', padding: '12px', fontSize: '0.875rem', lineHeight: '1.6', color: 'var(--text-muted)', whiteSpace: 'pre-wrap' }}>
                {cancResult.draftMessage}
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Status messages ───────────────────────────── */}
      {message && (
        <div
          className={'alert alert-' + (message.type === 'success' ? 'success' : 'error')}
          id='action-message'
        >
          {message.type === 'success' ? '✅ ' : '❌ '}
          {message.text}
        </div>
      )}
    </div>
  )
}
