import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'

export default async function ActivityPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/activity'))
    }
    redirect('/auth/signin')
  }

  let pageData: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }
  try {
    pageData = await coreApiFetch<{ items: any[]; totalElements: number }>({
      path: '/api/v1/audit-logs?page=0&size=100',
      token,
    })
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/activity'))
    }
  }

  const allAuditLogs = (pageData.items || []).map((log: any) => ({
    id: log.id,
    dealId: log.dealId,
    action: log.action,
    detail: log.detail,
    createdAt: log.createdAt,
  }))

  const getActionColor = (action: string) => {
    const act = (action || '').toLowerCase()
    if (act.includes('created')) return 'var(--warning)'
    if (act.includes('updated') || act.includes('progress')) return 'var(--accent)'
    if (act.includes('paid') || act.includes('completed')) return 'var(--success)'
    if (act.includes('cancelled') || act.includes('failed')) return 'var(--danger)'
    return 'var(--text-muted)'
  }

  const getActionIcon = (action: string) => {
    const act = (action || '').toLowerCase()
    if (act.includes('created')) return '✏️'
    if (act.includes('updated') || act.includes('progress')) return '🔄'
    if (act.includes('sent') || act.includes('reminder')) return '🔔'
    if (act.includes('failed') || act.includes('cancelled')) return '❌'
    if (act.includes('paid') || act.includes('payment')) return '💳'
    if (act.includes('completed')) return '✅'
    return '📝'
  }

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Activity</div>
          <div className='dashboard-subtitle'>Audit trail of all actions and changes</div>
        </div>
      </div>

      {pageData.totalElements > allAuditLogs.length && (
        <div className='alert alert-info' style={{ marginBottom: '24px' }}>
          Showing first {allAuditLogs.length} of {pageData.totalElements} activity logs
        </div>
      )}

      {/* Activity Timeline */}
      <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
        {allAuditLogs.length === 0 ? (
          <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
            No activity recorded yet.
          </div>
        ) : (
          <div style={{ padding: '24px' }}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
              {allAuditLogs.map((log) => {
                const date = new Date(log.createdAt).toLocaleDateString('en-IN', {
                  day: 'numeric',
                  month: 'short',
                  year: 'numeric',
                  hour: '2-digit',
                  minute: '2-digit',
                })

                return (
                  <div key={log.id} style={{ display: 'flex', gap: '16px', alignItems: 'flex-start' }}>
                    <div
                      style={{
                        width: '36px',
                        height: '36px',
                        borderRadius: '50%',
                        background: 'var(--surface2)',
                        border: '1px solid var(--border)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: '1rem',
                        flexShrink: 0,
                      }}
                    >
                      {getActionIcon(log.action)}
                    </div>

                    <div style={{ flex: 1 }}>
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '4px' }}>
                        <div style={{ fontWeight: 600, color: getActionColor(log.action), textTransform: 'capitalize' }}>
                          {log.action.replace(/_/g, ' ')}
                        </div>
                        <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                          {date}
                        </div>
                      </div>

                      <div style={{ color: 'var(--text)', fontSize: '0.875rem', marginBottom: '4px' }}>
                        {log.detail}
                      </div>

                      {log.dealId && (
                        <Link
                          href={'/dashboard/' + log.dealId}
                          style={{ fontSize: '0.75rem', color: 'var(--accent)' }}
                        >
                          View Deal #{log.dealId.slice(0, 8)} →
                        </Link>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
