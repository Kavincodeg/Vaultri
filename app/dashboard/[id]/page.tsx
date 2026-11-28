import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect, notFound } from 'next/navigation'
import Link from 'next/link'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch, NotFoundError } from '@/lib/core-api/server'
import { mapDealStatus, mapPaymentStatus, mapReminderStatus } from '@/lib/core-api/status-mapper'
import { DealActions } from '@/components/DealActions'
import { PaymentStatusPoller } from '@/components/PaymentStatusPoller'

function fmt(paise: number) { return 'INR ' + (paise/100).toLocaleString('en-IN') }
function statusLabel(s: string) {
  const m: Record<string,string> = { draft:'Draft', deposit_pending:'Deposit Pending', deposit_paid:'Deposit Paid', in_progress:'In Progress', completed:'Completed', cancelled:'Cancelled' }
  return m[s] ?? s
}
function statusCls(s: string) {
  const m: Record<string,string> = { draft:'badge-draft', deposit_pending:'badge-pending', deposit_paid:'badge-paid', in_progress:'badge-progress', completed:'badge-completed', cancelled:'badge-cancelled' }
  return m[s] ?? 'badge-draft'
}

export default async function DealDetailPage({ params }: { params: { id: string } }) {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/' + params.id))
    }
    redirect('/auth/signin')
  }

  let deal: any
  let auditLogsPage: { items: any[] } = { items: [] }
  let remindersPage: { items: any[] } = { items: [] }

  try {
    const [dealRes, auditRes, remindersRes] = await Promise.all([
      coreApiFetch<any>({ path: `/api/v1/deals/${encodeURIComponent(params.id)}`, token }),
      coreApiFetch<{ items: any[] }>({ path: `/api/v1/deals/${encodeURIComponent(params.id)}/audit-logs?page=0&size=100`, token }),
      coreApiFetch<{ items: any[] }>({ path: '/api/v1/reminders?page=0&size=100', token }),
    ])
    deal = dealRes
    auditLogsPage = auditRes
    remindersPage = remindersRes
  } catch (err) {
    if (err instanceof NotFoundError) {
      notFound()
    }
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/' + params.id))
    }
    notFound()
  }

  if (!deal) notFound()

  const uiStatus = mapDealStatus(deal.status)
  const depositPayment = deal.depositPayment
    ? {
        id: deal.depositPayment.id,
        amount: deal.depositPayment.amount,
        shortUrl: deal.depositPayment.paymentUrl,
        status: mapPaymentStatus(deal.depositPayment.status),
        type: 'deposit',
      }
    : null

  const cancPayment = deal.cancellationFeePayment
    ? {
        id: deal.cancellationFeePayment.id,
        amount: deal.cancellationFeePayment.amount,
        shortUrl: deal.cancellationFeePayment.paymentUrl,
        status: mapPaymentStatus(deal.cancellationFeePayment.status),
        type: 'cancellation_fee',
      }
    : null

  const dealReminders = (remindersPage.items || [])
    .filter((r: any) => r.dealId === deal.id)
    .map((r: any) => ({
      id: r.id,
      status: mapReminderStatus(r.status),
      sentAt: r.sentAt || null,
    }))

  const pendingReminders = dealReminders.filter((r: any) => r.status === 'scheduled' && !r.sentAt)

  // Calculate protection score (0-100)
  let protectionScore = 0
  const protectionItems = []

  if (deal.contractText) {
    protectionScore += 25
    protectionItems.push({ label: 'Contract Drafted', value: true })
  } else {
    protectionItems.push({ label: 'Contract Drafted', value: false })
  }

  if (depositPayment?.status === 'completed') {
    protectionScore += 25
    protectionItems.push({ label: 'Deposit Secured', value: true })
  } else {
    protectionItems.push({ label: 'Deposit Secured', value: false })
  }

  if (deal.contractText?.includes('IP') || deal.contractText?.includes('intellectual')) {
    protectionScore += 25
    protectionItems.push({ label: 'IP Protected', value: true })
  } else {
    protectionItems.push({ label: 'IP Protected', value: false })
  }

  if (dealReminders.length > 0) {
    protectionScore += 25
    protectionItems.push({ label: 'Payment Reminders', value: true })
  } else {
    protectionItems.push({ label: 'Payment Reminders', value: false })
  }

  return (
    <div className='container' style={{ paddingBottom: '64px' }}>
      <PaymentStatusPoller
        dealId={deal.id}
        initialDepositStatus={depositPayment?.status ?? null}
        initialDealStatus={uiStatus}
      />

      {/* Back button */}
      <div style={{ paddingTop: '24px', marginBottom: '16px' }}>
        <Link href='/dashboard' className='section-action'>
          ← Back to Dashboard
        </Link>
      </div>

      {/* Deal header banner */}
      <div className='card' style={{ marginBottom: '24px', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div>
          <div style={{ display: 'flex', alignItems: 'center', gap: '12px', marginBottom: '8px' }}>
            <span className={'badge ' + statusCls(uiStatus)} id='deal-status-badge'>
              {statusLabel(uiStatus)}
            </span>
            <span style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              Deal #{deal.id.slice(0, 8)}
            </span>
          </div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 700, margin: '0 0 4px 0' }}>{deal.title}</h1>
          <div style={{ color: 'var(--text-muted)', fontSize: '0.9rem' }}>
            Customer: <strong style={{ color: 'var(--text)' }}>{deal.customerName}</strong>
            {deal.customerPhone && ' • ' + deal.customerPhone}
            {deal.customerEmail && ' • ' + deal.customerEmail}
          </div>
        </div>

        {/* Protection Score */}
        <div style={{ textAlign: 'right', minWidth: '140px' }}>
          <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '4px' }}>Protection Score</div>
          <div style={{
            fontSize: '2rem',
            fontWeight: 800,
            color: protectionScore >= 75 ? 'var(--success)' : protectionScore >= 50 ? 'var(--warning)' : 'var(--danger)',
          }}>
            {protectionScore}%
          </div>
          <div style={{ display: 'flex', gap: '4px', justifyContent: 'flex-end', marginTop: '4px' }}>
            {protectionItems.map((item, idx) => (
              <span key={idx} title={item.label} style={{ fontSize: '0.75rem', opacity: item.value ? 1 : 0.25 }}>
                {item.value ? '✅' : '⚪'}
              </span>
            ))}
          </div>
        </div>
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: '1fr 360px', gap: '24px', alignItems: 'start' }}>
        {/* Left column */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
          {/* Deal financials */}
          <div className='card'>
            <div style={{ fontWeight: 600, marginBottom: '16px', fontSize: '1rem' }}>Financial Overview</div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '16px' }}>
              <div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Total Deal Value</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700, marginTop: '4px' }}>{fmt(deal.price)}</div>
              </div>
              <div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Deposit Required</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700, marginTop: '4px', color: 'var(--accent)' }}>
                  {fmt(deal.depositAmount || Math.round(deal.price * deal.depositPercent / 100))}
                  <span style={{ fontSize: '0.8rem', fontWeight: 400, marginLeft: '4px' }}>({deal.depositPercent}%)</span>
                </div>
              </div>
              <div>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Balance on Delivery</div>
                <div style={{ fontSize: '1.25rem', fontWeight: 700, marginTop: '4px' }}>
                  {fmt(deal.price - (deal.depositAmount || Math.round(deal.price * deal.depositPercent / 100)))}
                </div>
              </div>
            </div>

            {deal.dueDate && (
              <div style={{ marginTop: '16px', paddingTop: '16px', borderTop: '1px solid var(--border)', fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                📅 Target Delivery Date:{' '}
                <strong style={{ color: 'var(--text)' }}>
                  {new Date(deal.dueDate).toLocaleDateString('en-IN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })}
                </strong>
              </div>
            )}
          </div>

          {/* Deposit Payment Box */}
          {depositPayment && (
            <div className='card' style={{ border: depositPayment.status === 'completed' ? '1px solid rgba(34,197,94,0.4)' : '1px solid var(--border)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                <div style={{ fontWeight: 600 }}>Deposit Payment Link</div>
                <span className={'badge ' + (depositPayment.status === 'completed' ? 'badge-paid' : 'badge-pending')}>
                  {depositPayment.status === 'completed' ? 'Paid' : 'Pending'}
                </span>
              </div>
              <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)', marginBottom: '12px' }}>
                Amount: <strong>{fmt(depositPayment.amount)}</strong>
              </div>
              {depositPayment.shortUrl && depositPayment.status !== 'completed' && (
                <div className='link-box'>
                  <span className='link-box-url'>{depositPayment.shortUrl}</span>
                  <a href={depositPayment.shortUrl} target='_blank' rel='noopener noreferrer' className='btn btn-primary btn-sm' id='btn-pay-deposit'>
                    Pay Now ↗
                  </a>
                </div>
              )}
            </div>
          )}

          {/* Cancellation fee payment link if generated */}
          {cancPayment && (
            <div className='card' style={{ border: '1px solid rgba(239,68,68,0.4)' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '12px' }}>
                <div style={{ fontWeight: 600, color: 'var(--danger)' }}>Cancellation Fee Link</div>
                <span className={'badge ' + (cancPayment.status === 'completed' ? 'badge-paid' : 'badge-cancelled')}>
                  {cancPayment.status === 'completed' ? 'Paid' : 'Pending'}
                </span>
              </div>
              <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)', marginBottom: '12px' }}>
                Amount: <strong>{fmt(cancPayment.amount)}</strong>
              </div>
              {cancPayment.shortUrl && (
                <div className='link-box'>
                  <span className='link-box-url'>{cancPayment.shortUrl}</span>
                  <a href={cancPayment.shortUrl} target='_blank' rel='noopener noreferrer' className='btn btn-danger btn-sm'>
                    Open Link ↗
                  </a>
                </div>
              )}
            </div>
          )}

          {/* Contract text */}
          <div className='card'>
            <div style={{ fontWeight: 600, marginBottom: '12px', fontSize: '1rem' }}>Drafted Contract Terms</div>
            {deal.contractText ? (
              <div style={{
                background: 'var(--surface2)',
                border: '1px solid var(--border)',
                borderRadius: '8px',
                padding: '16px',
                fontSize: '0.875rem',
                lineHeight: '1.7',
                whiteSpace: 'pre-wrap',
                fontFamily: 'inherit',
                maxHeight: '320px',
                overflowY: 'auto',
              }}>
                {deal.contractText}
              </div>
            ) : (
              <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>No contract terms drafted yet.</p>
            )}
          </div>

          {/* Audit trail */}
          <div className='card'>
            <div style={{ fontWeight: 600, marginBottom: '12px', fontSize: '1rem' }}>Timeline & Audit Trail</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '10px' }}>
              {(auditLogsPage.items || []).map((log: any) => (
                <div key={log.id} style={{ display: 'flex', gap: '12px', alignItems: 'flex-start', fontSize: '0.85rem' }}>
                  <span style={{ color: 'var(--text-muted)', minWidth: '70px', fontSize: '0.75rem', paddingTop: '2px' }}>
                    {new Date(log.createdAt).toLocaleDateString('en-IN', { month: 'short', day: 'numeric' })}
                  </span>
                  <div>
                    <strong style={{ textTransform: 'capitalize' }}>{log.action.replace(/_/g, ' ')}</strong>
                    <div style={{ color: 'var(--text-muted)', marginTop: '2px' }}>{log.detail}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Right column: Action panel */}
        <div>
          <DealActions
            dealId={deal.id}
            status={uiStatus}
            totalPrice={deal.price}
            customerName={deal.customerName}
            description={deal.title}
            pendingReminderIds={pendingReminders.map((r: any) => r.id)}
            reminders={dealReminders}
          />
        </div>
      </div>
    </div>
  )
}
