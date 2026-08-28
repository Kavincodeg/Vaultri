import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect, notFound } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'
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
  if (!session?.user?.email) redirect('/auth/signin')

  const seller = await prisma.seller.findUnique({ where: { email: session.user.email } })
  if (!seller) redirect('/auth/signin')

  const deal = await prisma.deal.findFirst({
    where: { id: params.id, sellerId: seller.id },
    include: { payments: { orderBy: { createdAt: 'asc' } }, reminders: { orderBy: { sentAt: 'desc' } }, auditLogs: { orderBy: { createdAt: 'desc' } } },
  })
  if (!deal) notFound()

  const depositPayment = deal.payments.find(p => p.type === 'deposit')
  const cancPayment = deal.payments.find(p => p.type === 'cancellation_fee')
  const pendingReminders = deal.reminders.filter(r => r.status === 'scheduled' && !r.sentAt)
  const allReminders = deal.reminders.map(r => ({
    id: r.id,
    status: r.status,
    sentAt: r.sentAt ? r.sentAt.toISOString() : null,
  }))

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
  
  if (deal.reminders.length > 0) {
    protectionScore += 25
    protectionItems.push({ label: 'Payment Reminders', value: true })
  } else {
    protectionItems.push({ label: 'Payment Reminders', value: false })
  }

  return (
    <div className='container' style={{ paddingBottom: '64px' }}>
      <div className='page-header' style={{ display:'flex', justifyContent:'space-between', alignItems:'flex-start', flexWrap:'wrap', gap:'16px' }}>
        <div>
          <div style={{ display:'flex', alignItems:'center', gap:'12px', marginBottom:'8px' }}>
            <h1 className='page-title' style={{ margin:0 }}>{deal.customerName}</h1>
            <span className={'badge ' + statusCls(deal.status)}>{statusLabel(deal.status)}</span>
          </div>
          <p className='page-sub'>{deal.description}</p>
        </div>
        <Link href='/dashboard' className='btn btn-ghost btn-sm'>← All Deals</Link>
      </div>

      <div style={{ display:'grid', gridTemplateColumns:'1fr 340px', gap:'24px', alignItems:'start' }}>
        <div style={{ display:'flex', flexDirection:'column', gap:'20px' }}>
          <div className='card'>
            <div className='card-header'><span className='card-title'>📈 Deal Summary</span></div>
            <div style={{ display:'grid', gridTemplateColumns:'1fr 1fr', gap:'16px' }}>
              {[
                { label:'Total Price', value: fmt(deal.price) },
                { label:'Deposit (' + deal.depositPercent + '%)', value: fmt(Math.round(deal.price * deal.depositPercent / 100)) },
                { label:'Due Date', value: new Date(deal.dueDate).toLocaleDateString('en-IN', { day:'numeric', month:'long', year:'numeric' }) },
                { label:'Reminder Email', value: deal.customerEmail || seller.email },
              ].map(item => (
                <div key={item.label}>
                  <div style={{ fontSize:'0.75rem', color:'var(--text-muted)', textTransform:'uppercase', letterSpacing:'0.06em', marginBottom:'4px' }}>{item.label}</div>
                  <div style={{ fontWeight:600, wordBreak:'break-all' }}>{item.value}</div>
                </div>
              ))}
            </div>
          </div>

          <div className='card'>
            <div className='card-header'>
              <span className='card-title'>📄 AI-Drafted Contract Terms</span>
              <span style={{ fontSize:'0.75rem', color:'var(--text-muted)' }}>Suraksha Protection Layer</span>
            </div>
            {deal.contractText
              ? <pre className='contract-block'>{deal.contractText}</pre>
              : <p style={{ color:'var(--text-muted)', fontSize:'0.875rem' }}>Contract not yet drafted.</p>}
          </div>

          {deal.auditLogs.length > 0 && (
            <div className='card'>
              <div className='card-header'><span className='card-title'>📜 Immutable Audit Trail</span></div>
              <div className='stack'>
                {deal.auditLogs.map(log => (
                  <div key={log.id} style={{ display:'flex', justifyContent:'space-between', alignItems:'flex-start', padding:'10px 0', borderBottom:'1px solid var(--border)' }}>
                    <div>
                      <div style={{ fontWeight:600, fontSize:'0.875rem', color:'var(--accent-h)' }}>{log.action.replace(/_/g, ' ').toUpperCase()}</div>
                      <div style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginTop:'2px' }}>{log.detail}</div>
                    </div>
                    <span style={{ fontSize:'0.75rem', color:'var(--text-muted)' }}>
                      {new Date(log.createdAt).toLocaleTimeString('en-IN', { hour:'2-digit', minute:'2-digit' })}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div style={{ display:'flex', flexDirection:'column', gap:'16px' }}>
          {/* Protection Score Card */}
          <div className='card'>
            <div className='card-header'>
              <span className='card-title'>🛡️ Protection Score</span>
            </div>
            <div style={{ display:'flex', flexDirection:'column', alignItems:'center', gap:'16px', paddingBottom:'12px' }}>
              <div style={{
                width: '120px',
                height: '120px',
                borderRadius: '50%',
                background: `conic-gradient(var(--accent) ${protectionScore * 3.6}deg, var(--surface2) 0deg)`,
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'center',
              }}>
                <div style={{
                  width: '110px',
                  height: '110px',
                  borderRadius: '50%',
                  background: 'var(--surface)',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                  flexDirection: 'column',
                }}>
                  <div style={{ fontSize: '2rem', fontWeight: '700', color: 'var(--accent)' }}>{protectionScore}</div>
                  <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Score</div>
                </div>
              </div>
              <div style={{ width: '100%', display: 'flex', flexDirection: 'column', gap: '8px' }}>
                {protectionItems.map((item, idx) => (
                  <div key={idx} style={{ display: 'flex', alignItems: 'center', gap: '8px', fontSize: '0.875rem' }}>
                    <span style={{ color: item.value ? 'var(--success)' : 'var(--text-muted)' }}>
                      {item.value ? '✓' : '○'}
                    </span>
                    <span style={{ color: item.value ? 'var(--text)' : 'var(--text-muted)' }}>
                      {item.label}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {depositPayment && (
            <div className='card'>
              <div className='card-header'>
                <span className='card-title'>💳 Deposit Link</span>
                <span className={'badge ' + (depositPayment.status === 'completed' ? 'badge-paid' : 'badge-created')}>
                  {depositPayment.status === 'completed' ? '✅ Paid' : 'Awaiting payment'}
                </span>
              </div>
              <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginBottom:'12px' }}>
                Amount: <strong style={{ color:'var(--text)' }}>{fmt(depositPayment.amount)}</strong>
              </p>
              {depositPayment.status !== 'completed' && depositPayment.shortUrl && (
                <a
                  href={depositPayment.shortUrl}
                  className='btn btn-primary'
                  style={{ width:'100%', justifyContent:'center', marginBottom:'10px' }}
                >
                  💳 Pay Now
                </a>
              )}
              <p style={{ fontSize:'0.75rem', color:'var(--text-muted)' }}>Link ID: {depositPayment.razorpayLinkId}</p>
            </div>
          )}

          {cancPayment && (
            <div className='card'>
              <div className='card-header'>
                <span className='card-title'>⚠️ Cancellation Fee</span>
                <span className={'badge ' + (cancPayment.status === 'completed' ? 'badge-paid' : 'badge-pending')}>
                  {cancPayment.status === 'completed' ? '✅ Paid' : 'Pending'}
                </span>
              </div>
              <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginBottom:'12px' }}>
                Fee Amount: <strong style={{ color:'var(--text)' }}>{fmt(cancPayment.amount)}</strong>
              </p>
              {cancPayment.status !== 'completed' && cancPayment.shortUrl && (
                <a
                  href={cancPayment.shortUrl}
                  target='_blank'
                  rel='noopener noreferrer'
                  className='btn btn-danger'
                  style={{ width:'100%', justifyContent:'center' }}
                >
                  💳 Pay Cancellation Fee ↗
                </a>
              )}
            </div>
          )}

          <DealActions
            dealId={deal.id}
            status={deal.status}
            totalPrice={deal.price}
            customerName={deal.customerName}
            description={deal.description}
            pendingReminderIds={pendingReminders.map(r => r.id)}
            reminders={allReminders}
          />
        </div>
      </div>

      {/* Auto-refresh while deposit is pending — no manual reload needed */}
      <PaymentStatusPoller
        dealId={deal.id}
        initialDepositStatus={depositPayment?.status ?? null}
        initialDealStatus={deal.status}
      />
    </div>
  )
}