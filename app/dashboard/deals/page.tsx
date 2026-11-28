import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'
import { mapDealStatus } from '@/lib/core-api/status-mapper'

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

export default async function DealsPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/deals'))
    }
    redirect('/auth/signin')
  }

  let dealsPage: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }
  try {
    dealsPage = await coreApiFetch<{ items: any[]; totalElements: number }>({
      path: '/api/v1/deals?page=0&size=100',
      token,
    })
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/deals'))
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

  const totalDeals = dealsPage.totalElements
  const completedDeals = deals.filter((d) => d.status === 'completed').length
  const pendingDeals = deals.filter((d) => d.status === 'deposit_pending').length
  const inProgressDeals = deals.filter((d) => d.status === 'in_progress').length

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Deals</div>
          <div className='dashboard-subtitle'>Manage all your protected deals</div>
        </div>
        <Link href='/dashboard/new' className='btn btn-primary'>+ New Deal</Link>
      </div>

      {dealsPage.totalElements > deals.length && (
        <div className='alert alert-info' style={{ marginBottom: '24px' }}>
          Showing first {deals.length} of {dealsPage.totalElements} deals (metrics calculated on first {deals.length})
        </div>
      )}

      {/* Deal Metrics */}
      <div className='metrics-grid'>
        <div className='metric-card'>
          <div className='metric-icon'>📋</div>
          <div className='metric-label'>Total Deals</div>
          <div className='metric-value'>{totalDeals}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>✅</div>
          <div className='metric-label'>Completed</div>
          <div className='metric-value'>{completedDeals}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>⏳</div>
          <div className='metric-label'>In Progress</div>
          <div className='metric-value'>{inProgressDeals}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>⚠️</div>
          <div className='metric-label'>Pending Deposit</div>
          <div className='metric-value'>{pendingDeals}</div>
        </div>
      </div>

      {/* Deals Table */}
      <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
        {deals.length === 0 ? (
          <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
            No deals found.{' '}
            <Link href='/dashboard/new' style={{ color: 'var(--accent)' }}>
              Create your first deal →
            </Link>
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Customer</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Due Date</th>
                <th>Created</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {deals.map((deal) => {
                const { cls, label } = statusBadge(deal.status)
                const dueDate = deal.dueDate
                  ? new Date(deal.dueDate).toLocaleDateString('en-IN', {
                      day: 'numeric',
                      month: 'short',
                      year: 'numeric',
                    })
                  : '—'
                const createdAt = new Date(deal.createdAt).toLocaleDateString('en-IN', {
                  day: 'numeric',
                  month: 'short',
                  year: 'numeric',
                })

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
                    <td>{createdAt}</td>
                    <td>
                      <Link href={'/dashboard/' + deal.id} className='section-action'>
                        View Details →
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
