import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'
import { mapDealStatus, mapReminderStatus } from '@/lib/core-api/status-mapper'

function statusBadge(status: string) {
  const map: Record<string, string> = {
    draft: 'badge-draft',
    deposit_pending: 'badge-pending',
    deposit_paid: 'badge-paid',
    in_progress: 'badge-progress',
    completed: 'badge-completed',
    cancelled: 'badge-cancelled',
  }
  const label: Record<string, string> = {
    draft: 'Draft',
    deposit_pending: 'Deposit Pending',
    deposit_paid: 'Deposit Paid',
    in_progress: 'In Progress',
    completed: 'Completed',
    cancelled: 'Cancelled',
  }
  return { cls: map[status] ?? 'badge-draft', label: label[status] ?? status }
}

function formatRupees(paise: number) {
  return 'INR ' + (paise / 100).toLocaleString('en-IN')
}

function getAvatarLetter(name: string) {
  return name.charAt(0).toUpperCase()
}

export default async function DashboardPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard'))
    }
    redirect('/auth/signin')
  }

  let dealsPage: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }
  let remindersPage: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }

  try {
    const [dRes, rRes] = await Promise.all([
      coreApiFetch<{ items: any[]; totalElements: number }>({ path: '/api/v1/deals?page=0&size=100', token }),
      coreApiFetch<{ items: any[]; totalElements: number }>({ path: '/api/v1/reminders?page=0&size=100', token }),
    ])
    dealsPage = dRes
    remindersPage = rRes
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard'))
    }
  }

  const deals = (dealsPage.items || []).map((d: any) => ({
    id: d.id,
    customerName: d.customerName,
    description: d.title,
    title: d.title,
    price: d.price,
    dueDate: d.dueDate,
    status: mapDealStatus(d.status),
    createdAt: d.createdAt,
  }))

  const completedDeals = deals.filter((d) => d.status === 'completed').length
  const totalRevenue = deals.reduce((sum, d) => sum + d.price, 0)
  const upcomingDueDeals = deals.filter((d) => d.dueDate && new Date(d.dueDate) > new Date() && d.status !== 'completed').length
  const attentionNeeded = deals.filter((d) => d.status === 'deposit_pending').length

  const recentDeals = deals.slice(0, 5)

  const upcomingReminders = (remindersPage.items || [])
    .map((r: any) => ({
      id: r.id,
      dealId: r.dealId,
      customerName: r.dealTitle,
      message: `${r.kind === 'MANUAL' ? 'Manual' : 'Scheduled'} reminder`,
      status: mapReminderStatus(r.status),
      dueAt: r.dueAt,
    }))
    .filter((r) => r.status === 'scheduled')
    .slice(0, 5)

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Dashboard Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Overview</div>
          <div className='dashboard-subtitle'>Welcome back, {session.user.name || 'Seller'}</div>
        </div>
        <Link href='/dashboard/new' className='btn btn-primary'>+ New Deal</Link>
      </div>

      {dealsPage.totalElements > deals.length && (
        <div className='alert alert-info' style={{ marginBottom: '24px' }}>
          Showing first {deals.length} of {dealsPage.totalElements} deals (metrics calculated on first {deals.length})
        </div>
      )}

      {deals.length === 0 ? (
        <div className='empty-state'>
          <div className='empty-icon'>📋</div>
          <div className='empty-title'>No deals protected yet</div>
          <div className='empty-desc'>Create your first deal to get contract terms drafted and deposit link generated.</div>
          <Link href='/dashboard/new' className='btn btn-primary'>Create your first deal →</Link>
        </div>
      ) : (
        <>
          {/* Metrics Grid */}
          <div className='metrics-grid'>
            <div className='metric-card'>
              <div className='metric-icon'>🛡️</div>
              <div className='metric-label'>Protected Deals</div>
              <div className='metric-value'>{completedDeals}</div>
            </div>

            <div className='metric-card'>
              <div className='metric-icon'>💰</div>
              <div className='metric-label'>Total Revenue</div>
              <div className='metric-value'>{formatRupees(totalRevenue)}</div>
            </div>

            <div className='metric-card'>
              <div className='metric-icon'>⏳</div>
              <div className='metric-label'>Upcoming Due</div>
              <div className='metric-value'>{upcomingDueDeals}</div>
            </div>

            <div className='metric-card'>
              <div className='metric-icon'>⚠️</div>
              <div className='metric-label'>Attention Needed</div>
              <div className='metric-value'>{attentionNeeded}</div>
            </div>
          </div>

          {/* Recent Deals Section */}
          <div style={{ marginBottom: '32px' }}>
            <div className='section-header'>
              <div className='section-title'>Recent Deals</div>
              <Link href='/dashboard/deals' className='section-action'>View all →</Link>
            </div>

            <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
              {recentDeals.length === 0 ? (
                <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
                  No deals yet
                </div>
              ) : (
                <table className='deals-table'>
                  <thead>
                    <tr>
                      <th>Customer</th>
                      <th>Amount</th>
                      <th>Status</th>
                      <th>Due Date</th>
                      <th>Action</th>
                    </tr>
                  </thead>
                  <tbody>
                    {recentDeals.map((deal) => {
                      const { cls, label } = statusBadge(deal.status)
                      const dueDate = deal.dueDate
                        ? new Date(deal.dueDate).toLocaleDateString('en-IN', {
                            day: 'numeric',
                            month: 'short',
                            year: 'numeric',
                          })
                        : '—'

                      return (
                        <tr key={deal.id}>
                          <td>
                            <div className='deals-table-cell-customer'>
                              <div className='deal-avatar'>{getAvatarLetter(deal.customerName)}</div>
                              <div>
                                <div className='name'>{deal.customerName}</div>
                                <div className='desc'>{deal.description.substring(0, 30)}...</div>
                              </div>
                            </div>
                          </td>
                          <td>{formatRupees(deal.price)}</td>
                          <td>
                            <span className={'badge ' + cls}>{label}</span>
                          </td>
                          <td>{dueDate}</td>
                          <td>
                            <Link href={'/dashboard/' + deal.id} className='section-action'>
                              View →
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

          {/* Upcoming Reminders */}
          {upcomingReminders.length > 0 && (
            <div>
              <div className='section-header'>
                <div className='section-title'>Upcoming Reminders</div>
                <Link href='/dashboard/reminders' className='section-action'>View all →</Link>
              </div>

              <div className='reminders-panel'>
                {upcomingReminders.map((reminder) => (
                  <div key={reminder.id} className='reminders-item'>
                    <div className='reminder-info'>
                      <div className='reminder-type'>{reminder.message}</div>
                      <div className='reminder-date'>For: {reminder.customerName}</div>
                    </div>
                    <div className='reminder-days'>Scheduled</div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </>
      )}
    </div>
  )
}
