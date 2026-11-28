import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'
import { mapReminderStatus } from '@/lib/core-api/status-mapper'

function reminderStatusBadge(status: string) {
  const map: Record<string, string> = {
    scheduled: 'badge-pending',
    sent: 'badge-paid',
    failed: 'badge-cancelled',
    cancelled: 'badge-cancelled',
  }
  const label: Record<string, string> = {
    scheduled: 'Scheduled',
    sent: 'Sent',
    failed: 'Failed',
    cancelled: 'Cancelled',
  }
  return { cls: map[status] ?? 'badge-draft', label: label[status] ?? status }
}

export default async function RemindersPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/reminders'))
    }
    redirect('/auth/signin')
  }

  let pageData: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }
  try {
    pageData = await coreApiFetch<{ items: any[]; totalElements: number }>({
      path: '/api/v1/reminders?page=0&size=100',
      token,
    })
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/reminders'))
    }
  }

  const allReminders = (pageData.items || []).map((r: any) => ({
    id: r.id,
    dealId: r.dealId,
    dealTitle: r.dealTitle,
    customerName: r.dealTitle,
    channel: 'EMAIL',
    message: `${r.kind === 'MANUAL' ? 'Manual' : 'Scheduled'} deposit reminder`,
    status: mapReminderStatus(r.status),
    dueAt: r.dueAt,
    sentAt: r.sentAt,
    attempts: r.attempts,
  }))

  const totalReminders = pageData.totalElements
  const sentReminders = allReminders.filter((r) => r.status === 'sent').length
  const scheduledReminders = allReminders.filter((r) => r.status === 'scheduled').length
  const failedReminders = allReminders.filter((r) => r.status === 'failed').length

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Reminders</div>
          <div className='dashboard-subtitle'>Manage payment reminders for your deals</div>
        </div>
      </div>

      {pageData.totalElements > allReminders.length && (
        <div className='alert alert-info' style={{ marginBottom: '24px' }}>
          Showing first {allReminders.length} of {pageData.totalElements} reminders (metrics calculated on first {allReminders.length})
        </div>
      )}

      {/* Metrics */}
      <div className='metrics-grid'>
        <div className='metric-card'>
          <div className='metric-icon'>🔔</div>
          <div className='metric-label'>Total Reminders</div>
          <div className='metric-value'>{totalReminders}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>⏳</div>
          <div className='metric-label'>Scheduled</div>
          <div className='metric-value'>{scheduledReminders}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>✅</div>
          <div className='metric-label'>Sent</div>
          <div className='metric-value'>{sentReminders}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>❌</div>
          <div className='metric-label'>Failed</div>
          <div className='metric-value'>{failedReminders}</div>
        </div>
      </div>

      {/* Reminders Table */}
      <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
        {allReminders.length === 0 ? (
          <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
            No reminders scheduled yet.
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Deal</th>
                <th>Channel</th>
                <th>Status</th>
                <th>Due / Sent Date</th>
                <th>Attempts</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {allReminders.map((reminder) => {
                const { cls, label } = reminderStatusBadge(reminder.status)
                const dateDisplay = reminder.sentAt
                  ? new Date(reminder.sentAt).toLocaleDateString('en-IN', {
                      day: 'numeric',
                      month: 'short',
                      year: 'numeric',
                    })
                  : reminder.dueAt
                  ? new Date(reminder.dueAt).toLocaleDateString('en-IN', {
                      day: 'numeric',
                      month: 'short',
                      year: 'numeric',
                    })
                  : '—'

                return (
                  <tr key={reminder.id}>
                    <td>
                      <Link href={'/dashboard/' + reminder.dealId} style={{ color: 'var(--text)', fontWeight: 500 }}>
                        {reminder.dealTitle}
                      </Link>
                    </td>
                    <td>
                      <span className='badge badge-draft'>{reminder.channel}</span>
                    </td>
                    <td>
                      <span className={'badge ' + cls}>{label}</span>
                    </td>
                    <td>{dateDisplay}</td>
                    <td>{reminder.attempts}</td>
                    <td>
                      <Link href={'/dashboard/' + reminder.dealId} className='section-action'>
                        View Deal →
                      </Link>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
