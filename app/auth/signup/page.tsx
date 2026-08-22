'use client'
import { useState } from 'react'
import { useRouter } from 'next/navigation'
import { signIn } from 'next-auth/react'
import Link from 'next/link'

export default function SignUpPage() {
  const router = useRouter()
  const [name, setName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [phone, setPhone] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      const res = await fetch('/api/auth/signup', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, email, password, phone }),
      })
      const data = await res.json()
      if (!res.ok) throw new Error(data.error || 'Registration failed')
      const loginRes = await signIn('credentials', { email, password, redirect: false })
      if (loginRes?.error) throw new Error('Account created, but login failed')
      router.push('/dashboard')
    } catch (err: any) {
      setError(err.message || 'Something went wrong')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ minHeight: 'calc(100vh - 64px)', display: 'flex', alignItems: 'center', justifyContent: 'center', padding: '24px' }}>
      <div className='card' style={{ width: '100%', maxWidth: '420px' }}>
        <div style={{ textAlign: 'center', marginBottom: '24px' }}>
          <div style={{ fontSize: '2rem', marginBottom: '8px' }}>🛡️</div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 700 }}>Create Suraksha Account</h1>
          <p style={{ color: 'var(--text-muted)', fontSize: '0.875rem' }}>Protect your custom orders and deals</p>
        </div>
        <form onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className='form-group'>
            <label className='form-label'>Full Name</label>
            <input type='text' className='form-input' placeholder='e.g. Priya Sharma' value={name} onChange={e => setName(e.target.value)} required />
          </div>
          <div className='form-group'>
            <label className='form-label'>Email</label>
            <input type='email' className='form-input' placeholder='you@example.com' value={email} onChange={e => setEmail(e.target.value)} required />
          </div>
          <div className='form-group'>
            <label className='form-label'>Password</label>
            <input type='password' className='form-input' placeholder='????????' value={password} onChange={e => setPassword(e.target.value)} required minLength={6} />
          </div>
          <div className='form-group'>
            <label className='form-label'>Phone (optional)</label>
            <input type='tel' className='form-input' placeholder='+91 99999 00000' value={phone} onChange={e => setPhone(e.target.value)} />
          </div>
          {error && <div className='alert alert-error'>{error}</div>}
          <button type='submit' className='btn btn-primary' style={{ justifyContent: 'center' }} disabled={loading}>
            {loading ? <><span className='spinner'/> Creating account...</> : 'Create Account →'}
          </button>
        </form>
        <div style={{ marginTop: '20px', textAlign: 'center', fontSize: '0.875rem', color: 'var(--text-muted)' }}>
          Already have an account? <Link href='/auth/signin' style={{ color: 'var(--accent-h)' }}>Sign in</Link>
        </div>
      </div>
    </div>
  )
}