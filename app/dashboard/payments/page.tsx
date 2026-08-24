import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'

function paymentStatusBadge(status: string) {
  const map: Record<string, string> = {
    created: 'badge-pending',
    expired: 'badge-expired',
    completed: 'badge-paid',
    failed: 'badge-cancelled',
  }
  const label: Record<string, string> = {
    created: 'Pending',
    expired: 'Expired',
    completed: 'Paid',
    failed: 'Failed',
  }
  return { cls: map[status] ?? 'badge-draft', label: label[status] ?? status }
}

function formatRupees(paise: number) {
  return 'INR ' + (paise / 100).toLocaleString('en-IN')
}

export default async function PaymentsPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user?.email) redirect('/auth/signin')

  const seller = await prisma.seller.findUnique({
    where: { email: session.user.email },
    include: {
      deals: {
        include: {
          payments: { orderBy: { createdAt: 'desc' } },
        },
      },
    },
  })

  if (!seller) redirect('/auth/signin')

  // Flatten all payments across deals
  const allPayments = seller.deals.flatMap((deal) =>
    deal.payments.map((p) => ({
      ...p,
      dealId: deal.id,
      customerName: deal.customerName,
    }))
  )

  // Calculate payment stats
  const totalPayments = allPayments.length
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

      {/* Payment Metrics */}
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
            No payments yet
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Payment Link</th>
                <th>Customer</th>
                <th>Amount</th>
                <th>Type</th>
                <th>Status</th>
                <th>Created</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {allPayments.map((payment) => {
                const { cls, label } = paymentStatusBadge(payment.status)
                const createdDate = new Date(payment.createdAt).toLocaleDateString('en-IN', {
                  day: 'numeric',
                  month: 'short',
                  year: 'numeric',
                })

                return (
                  <tr key={payment.id}>
                    <td>
                      <code style={{ fontSize: '0.75rem', color: 'var(--accent)' }}>
                        {payment.razorpayLinkId?.substring(0, 12)}...
                      </code>
                    </td>
                    <td>{payment.customerName || 'N/A'}</td>
                    <td>{formatRupees(payment.amount)}</td>
                    <td style={{ textTransform: 'capitalize' }}>{payment.type}</td>
                    <td>
                      <span className={'badge ' + cls}>{label}</span>
                    </td>
                    <td>{createdDate}</td>
                    <td>
                      <Link href={`/dashboard/${payment.dealId}`} className='section-action'>
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
