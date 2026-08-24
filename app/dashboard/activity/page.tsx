import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'

export default async function ActivityPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user?.email) redirect('/auth/signin')

  const seller = await prisma.seller.findUnique({
    where: { email: session.user.email },
    include: {
      deals: {
        include: {
          auditLogs: { orderBy: { createdAt: 'desc' } },
        },
      },
    },
  })

  if (!seller) redirect('/auth/signin')

  // Flatten all audit logs across deals
  const allAuditLogs = seller.deals.flatMap((deal) =>
    deal.auditLogs.map((log) => ({
      ...log,
      dealId: deal.id,
      customerName: deal.customerName,
    }))
  )

  // Sort by date descending
  const sortedLogs = allAuditLogs.sort(
    (a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
  )

  const getActionColor = (action: string) => {
    if (action.includes('created')) return 'var(--warning)'
    if (action.includes('updated')) return 'var(--accent)'
    if (action.includes('sent')) return 'var(--success)'
    if (action.includes('failed')) return 'var(--danger)'
    return 'var(--text-muted)'
  }

  const getActionIcon = (action: string) => {
    if (action.includes('created')) return '✏️'
    if (action.includes('updated')) return '🔄'
    if (action.includes('sent')) return '📨'
    if (action.includes('failed')) return '❌'
    if (action.includes('contract')) return '📄'
    if (action.includes('payment')) return '💳'
    if (action.includes('reminder')) return '🔔'
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

      {/* Activity Stats */}
      <div className='metrics-grid'>
        <div className='metric-card'>
          <div className='metric-icon'>📝</div>
          <div className='metric-label'>Total Activities</div>
          <div className='metric-value'>{sortedLogs.length}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>📄</div>
          <div className='metric-label'>Contracts Created</div>
          <div className='metric-value'>
            {sortedLogs.filter((l) => l.action.includes('contract')).length}
          </div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>💳</div>
          <div className='metric-label'>Payments Generated</div>
          <div className='metric-value'>
            {sortedLogs.filter((l) => l.action.includes('payment')).length}
          </div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>🔔</div>
          <div className='metric-label'>Reminders Sent</div>
          <div className='metric-value'>
            {sortedLogs.filter((l) => l.action.includes('reminder')).length}
          </div>
        </div>
      </div>

      {/* Activity Timeline */}
      <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', padding: '20px' }}>
        {sortedLogs.length === 0 ? (
          <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
            No activities yet
          </div>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
            {sortedLogs.map((log, idx) => {
              const createdDate = new Date(log.createdAt).toLocaleDateString('en-IN', {
                day: 'numeric',
                month: 'short',
                year: 'numeric',
              })
              const createdTime = new Date(log.createdAt).toLocaleTimeString('en-IN', {
                hour: '2-digit',
                minute: '2-digit',
              })

              return (
                <div
                  key={idx}
                  style={{
                    display: 'flex',
                    gap: '12px',
                    paddingBottom: '16px',
                    borderBottom: idx < sortedLogs.length - 1 ? '1px solid var(--border)' : 'none',
                  }}
                >
                  <div
                    style={{
                      fontSize: '1.25rem',
                      minWidth: '40px',
                      display: 'flex',
                      alignItems: 'center',
                    }}
                  >
                    {getActionIcon(log.action)}
                  </div>
                  <div style={{ flex: 1 }}>
                    <div
                      style={{
                        fontWeight: 600,
                        color: getActionColor(log.action),
                        textTransform: 'capitalize',
                        marginBottom: '4px',
                      }}
                    >
                      {log.action}
                    </div>
                    <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)', marginBottom: '4px' }}>
                      {log.detail}
                    </div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      <Link href={`/dashboard/${log.dealId}`} className='section-action' style={{ marginRight: '16px' }}>
                        {log.customerName}
                      </Link>
                      <span>{createdTime} on {createdDate}</span>
                    </div>
                  </div>
                </div>
              )
            })}
          </div>
        )}
      </div>
    </div>
  )
}
