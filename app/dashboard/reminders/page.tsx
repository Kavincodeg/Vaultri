import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'

function reminderStatusBadge(status: string) {
  const map: Record<string, string> = {
    scheduled: 'badge-pending',
    sent: 'badge-paid',
    failed: 'badge-cancelled',
  }
  const label: Record<string, string> = {
    scheduled: 'Scheduled',
    sent: 'Sent',
    failed: 'Failed',
  }
  return { cls: map[status] ?? 'badge-draft', label: label[status] ?? status }
}

export default async function RemindersPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user?.email) redirect('/auth/signin')

  const seller = await prisma.seller.findUnique({
    where: { email: session.user.email },
    include: {
      deals: {
        include: {
          reminders: { orderBy: { createdAt: 'desc' } },
        },
      },
    },
  })

  if (!seller) redirect('/auth/signin')

  // Flatten all reminders across deals
  const allReminders = seller.deals.flatMap((deal) =>
    deal.reminders.map((r) => ({
      ...r,
      dealId: deal.id,
      customerName: deal.customerName,
    }))
  )

  // Calculate reminder stats
  const totalReminders = allReminders.length
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
        <Link href='/dashboard/new' className='btn btn-primary'>+ New Reminder</Link>
      </div>

      {/* Reminder Metrics */}
      <div className='metrics-grid'>
        <div className='metric-card'>
          <div className='metric-icon'>🔔</div>
          <div className='metric-label'>Total Reminders</div>
          <div className='metric-value'>{totalReminders}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>✅</div>
          <div className='metric-label'>Sent</div>
          <div className='metric-value'>{sentReminders}</div>
        </div>

        <div className='metric-card'>
          <div className='metric-icon'>⏳</div>
          <div className='metric-label'>Scheduled</div>
          <div className='metric-value'>{scheduledReminders}</div>
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
            No reminders yet
          </div>
        ) : (
          <table className='deals-table'>
            <thead>
              <tr>
                <th>Recipient</th>
                <th>Message</th>
                <th>Channel</th>
                <th>Status</th>
                <th>Scheduled Date</th>
                <th>Attempts</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {allReminders.map((reminder) => {
                const { cls, label } = reminderStatusBadge(reminder.status)
                const scheduledDate = reminder.sentAt
                  ? new Date(reminder.sentAt).toLocaleDateString('en-IN', {
                      day: 'numeric',
                      month: 'short',
                      year: 'numeric',
                    })
                  : 'N/A'

                return (
                  <tr key={reminder.id}>
                    <td>{reminder.customerName || 'N/A'}</td>
                    <td style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
                      {reminder.message.substring(0, 50)}...
                    </td>
                    <td style={{ textTransform: 'capitalize' }}>{reminder.channel}</td>
                    <td>
                      <span className={'badge ' + cls}>{label}</span>
                    </td>
                    <td>{scheduledDate}</td>
                    <td>{reminder.attempts}</td>
                    <td>
                      <Link href={`/dashboard/${reminder.dealId}`} className='section-action'>
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
