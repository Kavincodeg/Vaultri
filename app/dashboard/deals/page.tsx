import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'

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
  if (!session?.user?.email) redirect('/auth/signin')

  const seller = await prisma.seller.findUnique({
    where: { email: session.user.email },
    include: {
      deals: {
        orderBy: { createdAt: 'desc' },
        include: { payments: true, reminders: true },
      },
    },
  })

  if (!seller) redirect('/auth/signin')

  const deals = seller.deals

  // Calculate stats
  const totalDeals = deals.length
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
            No deals yet. <Link href='/dashboard/new' className='section-action'>Create your first deal →</Link>
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Customer</th>
                <th>Description</th>
                <th>Amount</th>
                <th>Deposit %</th>
                <th>Due Date</th>
                <th>Status</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {deals.map((deal) => {
                const { cls, label } = statusBadge(deal.status)
                const dueDate = new Date(deal.dueDate).toLocaleDateString('en-IN', {
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
                          {deal.customerEmail && (
                            <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                              {deal.customerEmail}
                            </div>
                          )}
                        </div>
                      </div>
                    </td>
                    <td style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>
                      {deal.description.substring(0, 40)}...
                    </td>
                    <td>{formatRupees(deal.price)}</td>
                    <td>{deal.depositPercent}%</td>
                    <td>{dueDate}</td>
                    <td>
                      <span className={'badge ' + cls}>{label}</span>
                    </td>
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
  )
}
