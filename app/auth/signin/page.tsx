'use client'
import { useState } from 'react'
import { signIn } from 'next-auth/react'
import { useRouter } from 'next/navigation'
import Link from 'next/link'

export default function SignInPage() {
  const router = useRouter()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await signIn('credentials', { email, password, redirect: false })
      if (res?.error) throw new Error('Invalid email or password')
      router.push('/dashboard')
    } catch (err: any) {
      setError(err.message || 'Sign in failed')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div className='card' style={{ width: '100%', maxWidth: '420px' }}>
        <div style={{ textAlign: 'center', marginBottom: '24px' }}>
          <div style={{ fontSize: '2rem', marginBottom: '8px' }}>🛡️</div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 700 }}>Sign in to Suraksha</h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Welcome back to your protected seller portal</p>
        </div>
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className='form-group'>
            <label className='form-label'>Email address</label>
            <input type='email' className='form-input' placeholder='demo@seller.com' value={email} onChange={e => setEmail(e.target.value)} required autoFocus />
          </div>
          <div className='form-group'>
            <label className='form-label'>Password</label>
            <input type='password' className='form-input' placeholder='????????' value={password} onChange={e => setPassword(e.target.value)} required />
          </div>
          {error && <div className='alert alert-error'>{error}</div>}
          <button type='submit' className='btn btn-primary' style={{ justifyContent: 'center' }} disabled={loading}>
            {loading ? <><span className='spinner'/> Signing in...</> : 'Sign in →'}
          </button>
        </form>
        <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '0.875rem', color: 'var(--text-muted)' }}>
          Need an account? <Link href='/auth/signup' style={{ color: 'var(--accent-h)' }}>Create Account</Link>
        </div>
      </div>
    </div>
  )
}