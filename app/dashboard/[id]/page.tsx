import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect, notFound } from 'next/navigation'
import { prisma } from '@/lib/prisma'
import Link from 'next/link'
import { DealActions } from '@/components/DealActions'

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
  const pendingReminders = deal.reminders.filter(r => !r.sentAt)

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
                { label:'Created', value: new Date(deal.createdAt).toLocaleDateString('en-IN') },
              ].map(item => (
                <div key={item.label}>
                  <div style={{ fontSize:'0.75rem', color:'var(--text-muted)', textTransform:'uppercase', letterSpacing:'0.06em', marginBottom:'4px' }}>{item.label}</div>
                  <div style={{ fontWeight:600 }}>{item.value}</div>
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
          {depositPayment && (
            <div className='card'>
              <div className='card-header'>
                <span className='card-title'>💳 Deposit Link</span>
                <span className={'badge ' + (depositPayment.status === 'paid' ? 'badge-paid' : 'badge-created')}>
                  {depositPayment.status === 'paid' ? '✅ Paid' : 'Awaiting payment'}
                </span>
              </div>
              <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)', marginBottom:'12px' }}>
                Amount: <strong style={{ color:'var(--text)' }}>{fmt(depositPayment.amount)}</strong>
              </p>
              <p style={{ fontSize:'0.75rem', color:'var(--text-muted)' }}>Link ID: {depositPayment.razorpayLinkId}</p>
            </div>
          )}

          {cancPayment && (
            <div className='card'>
              <div className='card-header'>
                <span className='card-title'>⚠️ Cancellation Fee</span>
                <span className={'badge ' + (cancPayment.status === 'paid' ? 'badge-paid' : 'badge-pending')}>
                  {cancPayment.status === 'paid' ? '✅ Paid' : 'Pending'}
                </span>
              </div>
              <p style={{ fontSize:'0.8125rem', color:'var(--text-muted)' }}>
                Fee Amount: <strong style={{ color:'var(--text)' }}>{fmt(cancPayment.amount)}</strong>
              </p>
            </div>
          )}

          <DealActions
            dealId={deal.id}
            status={deal.status}
            totalPrice={deal.price}
            customerName={deal.customerName}
            description={deal.description}
            pendingReminderIds={pendingReminders.map(r => r.id)}
          />
        </div>
      </div>
    </div>
  )
}