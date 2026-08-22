'use client'
import { useState } from 'react'
import { useRouter } from 'next/navigation'

interface Props {
  dealId: string
  status: string
  totalPrice: number
  customerName: string
  description: string
  pendingReminderIds: string[]
}

export function DealActions({ dealId, status, totalPrice, customerName, description, pendingReminderIds }: Props) {
  const router = useRouter()
  const [loading, setLoading] = useState<string | null>(null)
  const [message, setMessage] = useState<{ type: 'success'|'error', text: string } | null>(null)
  const [cancResult, setCancResult] = useState<{ shortUrl: string, draftMessage: string, feeAmount: number } | null>(null)

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
  const canCancel = !isCancelled && status !== 'draft'

  return (
    <div style={{ display:'flex', flexDirection:'column', gap:'12px' }}>

      {/* Reminder trigger */}
      {pendingReminderIds.length > 0 && (
        <div className='card'>
          <div style={{ fontWeight:600, marginBottom:'10px', fontSize:'0.9rem' }}>🔔 Send Reminder</div>
          <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginBottom:'12px', lineHeight:'1.5' }}>
            Manually trigger the scheduled payment reminder. Customer will receive an email.
          </p>
          <button
            id='btn-send-reminder'
            className='btn btn-secondary'
            style={{ width:'100%', justifyContent:'center' }}
            onClick={() => handleSendReminder(pendingReminderIds[0])}
            disabled={loading === 'reminders'}
          >
            {loading === 'reminders' ? <><span className='spinner'/>&nbsp;Sending...</> : '🔔 Run Reminder Check'}
          </button>
        </div>
      )}

      {/* Cancellation flow ? FM-1 */}
      {canCancel && !cancResult && (
        <div className='card' style={{ border:'1px solid rgba(239,68,68,0.3)' }}>
          <div style={{ fontWeight:600, marginBottom:'10px', fontSize:'0.9rem', color:'var(--danger)' }}>⚠️ Customer Cancelled?</div>
          <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginBottom:'12px', lineHeight:'1.5' }}>
            If the customer cancels after work has started, the AI agent will generate a cancellation fee link and a polite message for you to send them.
          </p>
          <button
            id='btn-trigger-cancellation'
            className='btn btn-danger'
            style={{ width:'100%', justifyContent:'center' }}
            onClick={handleCancellation}
            disabled={loading === 'cancellation'}
          >
            {loading === 'cancellation'
              ? <><span className='spinner'/>&nbsp;AI generating fee link...</>
              : '🛑 Generate Cancellation Fee'}
          </button>
        </div>
      )}

      {/* Cancellation result with draft message */}
      {cancResult && (
        <div className='card' style={{ border:'1px solid rgba(239,68,68,0.3)' }}>
          <div style={{ fontWeight:600, marginBottom:'10px', fontSize:'0.9rem', color:'var(--danger)' }}>🛑 Cancellation Fee Generated</div>
          <div className='link-box' style={{ marginBottom:'12px' }}>
            <span className='link-box-url'>{cancResult.shortUrl}</span>
            <a href={cancResult.shortUrl} target='_blank' rel='noopener noreferrer' className='btn btn-danger btn-sm' id='btn-canc-link'>Open ↗</a>
          </div>
          {cancResult.draftMessage && (
            <div>
              <div style={{ fontSize:'0.75rem', color:'var(--text-muted)', marginBottom:'6px', textTransform:'uppercase', letterSpacing:'0.06em' }}>Draft message for customer</div>
              <div style={{ background:'var(--surface2)', border:'1px solid var(--border)', borderRadius:'8px', padding:'12px', fontSize:'0.875rem', lineHeight:'1.6', color:'var(--text-muted)', whiteSpace:'pre-wrap' }}>
                {cancResult.draftMessage}
              </div>
            </div>
          )}
        </div>
      )}

      {/* Status messages */}
      {message && (
        <div className={'alert alert-' + (message.type === 'success' ? 'success' : 'error')} id='action-message'>
          {message.type === 'success' ? '✅ ' : '❌ '}{message.text}
        </div>
      )}
    </div>
  )
}