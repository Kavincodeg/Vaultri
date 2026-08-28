'use client'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { useSession } from 'next-auth/react'

const EXAMPLES = [
  'Priya wants a custom gold necklace, total INR 8000, needs it by September 15',
  'Ravi ordered a bespoke silk kurta set, INR 4500, delivery by October 1',
  'Meena wants 3 dozen custom cookies for a wedding, INR 3600 total, needed by Aug 30',
  'Deepa commissioned a hand-painted portrait, INR 12000, due in 3 weeks',
]

export default function NewDealPage() {
  const { status } = useSession()
  const router = useRouter()
  const [description, setDescription] = useState('')
  const [customerName, setCustomerName] = useState('')
  const [customerEmail, setCustomerEmail] = useState('')
  const [customerPhone, setCustomerPhone] = useState('')
  const [price, setPrice] = useState('')
  const [dueDate, setDueDate] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  if (status === 'unauthenticated') { router.push('/auth/signin'); return null }

  const tomorrow = new Date()
  tomorrow.setDate(tomorrow.getDate() + 1)
  const minDate = tomorrow.toISOString().split('T')[0]

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    const priceNum = parseFloat(price)
    if (!priceNum || priceNum <= 0) { setError('Enter a valid price'); return }
    if (!dueDate) { setError('Due date is required'); return }
    if (!description.trim()) { setError('Deal description is required'); return }
    if (!customerName.trim()) { setError('Customer name is required'); return }
    setLoading(true)
    try {
      const res = await fetch('/api/deals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          description: description.trim(),
          customerName: customerName.trim(),
          customerEmail: customerEmail.trim() || null,
          customerPhone: customerPhone.trim() || null,
          price: Math.round(priceNum * 100),
          dueDate: new Date(dueDate).toISOString(),
        }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error ?? 'Failed to create deal')
      router.push('/dashboard/' + data.dealId)
    } catch (err: any) {
      setError(err.message ?? 'Something went wrong')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className='container' style={{ paddingBottom: '64px' }}>
      <div className='page-header'>
        <h1 className='page-title'>Create a New Deal</h1>
        <p className='page-sub'>Describe the deal in plain language. The AI agent will draft the contract and create a deposit link automatically.</p>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: '32px', alignItems: 'start' }}>
        <form onSubmit={handleSubmit} id='form-create-deal'>
          <div className='card' style={{ display: 'flex', flexDirection: 'column', gap: '20px' }}>
            <div className='form-group'>
              <label className='form-label' htmlFor='deal-description'>
                Describe the deal <span style={{ color: 'var(--accent-h)' }}>*</span>
              </label>
              <textarea
                id='deal-description'
                className='form-textarea'
                placeholder='e.g. Priya wants a custom gold necklace, total INR 8000, needs it by September 15'
                value={description}
                onChange={e => setDescription(e.target.value)}
                style={{ minHeight: '120px' }}
                required
              />
              <p className='form-hint'>Write naturally ? the AI decides deposit %, cancellation terms, reminder timing and tone.</p>
            </div>
            <hr className='divider' />
            <div className='form-grid'>
              <div className='form-group'>
                <label className='form-label' htmlFor='customer-name'>Customer name <span style={{ color: 'var(--accent-h)' }}>*</span></label>
                <input id='customer-name' type='text' className='form-input' placeholder='e.g. Priya Sharma' value={customerName} onChange={e => setCustomerName(e.target.value)} required />
              </div>
              <div className='form-group'>
                <label className='form-label' htmlFor='customer-email'>Customer email (optional — for reminders)</label>
                <input id='customer-email' type='email' className='form-input' placeholder='e.g. priya@example.com' value={customerEmail} onChange={e => setCustomerEmail(e.target.value)} />
              </div>
            </div>
            <div className='form-grid'>
              <div className='form-group'>
                <label className='form-label' htmlFor='customer-phone'>Customer phone (optional)</label>
                <input id='customer-phone' type='tel' className='form-input' placeholder='+91 99999 00000' value={customerPhone} onChange={e => setCustomerPhone(e.target.value)} />
              </div>
              <div className='form-group'>
                <label className='form-label' htmlFor='deal-price'>Total price (INR) <span style={{ color: 'var(--accent-h)' }}>*</span></label>
                <input id='deal-price' type='number' className='form-input' placeholder='e.g. 8000' value={price} onChange={e => setPrice(e.target.value)} min='1' step='1' required />
              </div>
            </div>
            <div className='form-group'>
              <label className='form-label' htmlFor='deal-due-date'>Due / delivery date <span style={{ color: 'var(--accent-h)' }}>*</span></label>
              <input id='deal-due-date' type='date' className='form-input' value={dueDate} onChange={e => setDueDate(e.target.value)} min={minDate} required />
            </div>
            {error && <div className='alert alert-error' id='form-error'>{error}</div>}
            <button id='btn-create-deal-submit' type='submit' className='btn btn-primary'
              style={{ width: '100%', justifyContent: 'center', padding: '14px' }} disabled={loading}>
              {loading
                ? <><span className='spinner' />&nbsp;AI agent drafting contract + creating deposit link...</>  
                : '🛡️ Create Deal — Draft Contract + Deposit Link'}
            </button>
          </div>
        </form>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className='card'>
            <div style={{ fontWeight: 600, marginBottom: '12px', fontSize: '0.9rem' }}>💡 Example deals</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
              {EXAMPLES.map((ex, i) => (
                <button key={i} id={'btn-example-'+i} type='button' className='btn btn-ghost btn-sm'
                  style={{ textAlign: 'left', whiteSpace: 'normal', height: 'auto', padding: '10px 12px', lineHeight: '1.4' }}
                  onClick={() => setDescription(ex)}>{ex}</button>
              ))}
            </div>
          </div>
          <div className='card'>
            <div style={{ fontWeight: 600, marginBottom: '14px', fontSize: '0.9rem' }}>⚡ What happens on submit</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
              {[
                { icon: '🤖', step: 'AI reads your deal description' },
                { icon: '📄', step: 'Drafts contract with deposit %, cancellation, late-payment & IP clauses' },
                { icon: '💳', step: 'Creates a real Razorpay deposit link (test mode)' },
                { icon: '🔔', step: 'Schedules a payment reminder 2 days before due date' },
              ].map((item, i) => (
                <div key={i} style={{ display: 'flex', gap: '10px', alignItems: 'flex-start' }}>
                  <span style={{ fontSize: '1.1rem', flexShrink: 0 }}>{item.icon}</span>
                  <span style={{ fontSize: '0.875rem', color: 'var(--text-muted)', lineHeight: '1.5' }}>{item.step}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>
    </div>
  )
}