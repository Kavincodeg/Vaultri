'use client'
import { useState } from 'react'
import { signIn } from 'next-auth/react'

export default function SignInPage() {
  const [email, setEmail] = useState('')
  const [sent, setSent] = useState(false)
  const [loading, setLoading] = useState(false)
  const isDev = process.env.NODE_ENV === 'development'

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setLoading(true)
    await signIn('email', { email, callbackUrl: '/dashboard', redirect: false })
    setSent(true)
    setLoading(false)
  }

  function handleDevSignin() {
    window.location.href = '/api/dev-signin'
  }

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div style={{ width: '100%', maxWidth: '420px', display: 'flex', flexDirection: 'column', gap: '16px' }}>

        {/* Dev quick signin */}
        <div className='card' style={{ border: '1px solid rgba(99,102,241,0.4)', background: 'rgba(99,102,241,0.06)' }}>
          <div style={{ fontSize: '0.75rem', color: 'var(--accent-h)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: '10px', fontWeight: 600 }}>
            🤖 Demo / Dev Mode
          </div>
          <p style={{ fontSize: '0.875rem', color: 'var(--text-muted)', marginBottom: '14px', lineHeight: '1.5' }}>
            Sign in instantly as the demo seller to test the full flow.
          </p>
          <button
            id='btn-dev-signin'
            className='btn btn-primary'
            style={{ width: '100%', justifyContent: 'center' }}
            onClick={handleDevSignin}
          >
            ⚡ Quick Sign-in (demo@seller.com)
          </button>
        </div>

        {/* Divider */}
        <div style={{ display: 'flex', alignItems: 'center', gap: '12px' }}>
          <div style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
          <span style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>or sign in with email</span>
          <div style={{ flex: 1, height: '1px', background: 'var(--border)' }} />
        </div>

        {/* Real magic link form */}
        <div className='card'>
          {sent ? (
            <div className='alert alert-success'>✅ Magic link sent! Check your inbox and click the link to sign in.</div>
          ) : (
            <form onSubmit={handleSubmit}>
              <div className='form-group' style={{ marginBottom: '16px' }}>
                <label className='form-label' htmlFor='email-input'>Email address</label>
                <input id='email-input' type='email' className='form-input' placeholder='you@example.com'
                  value={email} onChange={e => setEmail(e.target.value)} required autoFocus />
              </div>
              <button id='btn-send-magic-link' type='submit' className='btn btn-secondary'
                style={{ width: '100%', justifyContent: 'center' }} disabled={loading}>
                {loading ? <><span className='spinner'/>&nbsp;Sending…</> : 'Send magic link →'}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  )
}