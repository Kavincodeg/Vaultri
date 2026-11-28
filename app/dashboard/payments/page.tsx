import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'
import { mapPaymentStatus, mapPaymentType } from '@/lib/core-api/status-mapper'

function paymentStatusBadge(status: string) {
  const map: Record<string, string> = {
    created: 'badge-pending',
    expired: 'badge-expired',
    completed: 'badge-paid',
    failed: 'badge-cancelled',
    cancelled: 'badge-cancelled',
  }
  const label: Record<string, string> = {
    created: 'Pending',
    expired: 'Expired',
    completed: 'Paid',
    failed: 'Failed',
    cancelled: 'Cancelled',
  }
  return { cls: map[status] ?? 'badge-draft', label: label[status] ?? status }
}

function formatRupees(paise: number) {
  return 'INR ' + (paise / 100).toLocaleString('en-IN')
}

export default async function PaymentsPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/payments'))
    }
    redirect('/auth/signin')
  }

  let pageData: { items: any[]; totalElements: number } = { items: [], totalElements: 0 }
  try {
    pageData = await coreApiFetch<{ items: any[]; totalElements: number }>({
      path: '/api/v1/payments?page=0&size=100',
      token,
    })
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/payments'))
    }
  }

  const allPayments = (pageData.items || []).map((p: any) => ({
    id: p.id,
    dealId: p.dealId,
    dealTitle: p.dealTitle,
    customerName: p.dealTitle,
    type: mapPaymentType(p.type),
    status: mapPaymentStatus(p.status),
    amount: p.amount,
    shortUrl: p.paymentUrl,
    createdAt: p.createdAt,
  }))

  const totalPayments = pageData.totalElements
  const paidPayments = allPayments.filter((p) => p.status === 'completed').length
  const pendingPayments = allPayments.filter((p) => p.status === 'created').length
  const totalAmount = allPayments.reduce((sum, p) => sum + p.amount, 0)

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Payments</div>
          <div className='dashboard-subtitle'>Track all your payment links and status</div>
        </div>
      </div>

      {pageData.totalElements > allPayments.length && (
        <div className='alert alert-info' style={{ marginBottom: '24px' }}>
          Showing first {allPayments.length} of {pageData.totalElements} payments (metrics calculated on first {allPayments.length})
        </div>
      )}

      {/* Metrics */}
      <div className='metrics-grid'>
        <div className='metric-card'>
          <div className='metric-icon'>💳</div>
          <div className='metric-label'>Total Payments</div>
          <div className='metric-value'>{totalPayments}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>✅</div>
          <div className='metric-label'>Paid</div>
          <div className='metric-value'>{paidPayments}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>⏳</div>
          <div className='metric-label'>Pending</div>
          <div className='metric-value'>{pendingPayments}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>💰</div>
          <div className='metric-label'>Total Amount</div>
          <div className='metric-value'>{formatRupees(totalAmount)}</div>
        </div>
      </div>

      {/* Payments Table */}
      <div style={{ background: 'var(--surface)', border: '1px solid var(--border)', borderRadius: 'var(--radius)', overflow: 'hidden' }}>
        {allPayments.length === 0 ? (
          <div style={{ padding: '32px', textAlign: 'center', color: 'var(--text-muted)' }}>
            No payments found yet.
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Deal</th>
                <th>Type</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Link</th>
                <th>Created</th>
              </tr>
            </thead>
            <tbody>
              {allPayments.map((payment) => {
                const { cls, label } = paymentStatusBadge(payment.status)
                const createdAt = new Date(payment.createdAt).toLocaleDateString('en-IN', {
                  day: 'numeric',
                  month: 'short',
                  year: 'numeric',
                })

                return (
                  <tr key={payment.id}>
                    <td>
                      <Link href={'/dashboard/' + payment.dealId} style={{ color: 'var(--text)', fontWeight: 500 }}>
                        {payment.dealTitle}
                      </Link>
                    </td>
                    <td>
                      <span style={{ textTransform: 'capitalize' }}>
                        {payment.type.replace('_', ' ')}
                      </span>
                    </td>
                    <td>{formatRupees(payment.amount)}</td>
                    <td>
                      <span className={'badge ' + cls}>{label}</span>
                    </td>
                    <td>
                      {payment.shortUrl ? (
                        <a href={payment.shortUrl} target='_blank' rel='noopener noreferrer' className='section-action'>
                          Open ↗
                        </a>
                      ) : (
                        '—'
                      )}
                    </td>
                    <td>{createdAt}</td>
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
