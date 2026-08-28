'use client'

import { useState, useEffect } from 'react'

interface ConfigStatus {
  razorpay: {
    configured: boolean
    keyIdPrefix: string
    webhookSecretConfigured: boolean
    webhookUrl: string
  }
  email: {
    configured: boolean
    sender: string
    service: string
  }
  ai: {
    configured: boolean
    model: string
  }
  qstash: {
    configured: boolean
    cronUrl: string
  }
}

export function ApiConfigSection() {
  const [isOpen, setIsOpen] = useState(false)
  const [config, setConfig] = useState<ConfigStatus | null>(null)
  const [loading, setLoading] = useState(false)
  const [testingEmail, setTestingEmail] = useState(false)
  const [testingRazorpay, setTestingRazorpay] = useState(false)
  const [emailResult, setEmailResult] = useState<{ success: boolean; message: string } | null>(null)
  const [razorpayResult, setRazorpayResult] = useState<{ success: boolean; message: string } | null>(null)
  const [copiedKey, setCopiedKey] = useState<string | null>(null)

  useEffect(() => {
    if (isOpen && !config) {
      loadConfig()
    }
  }, [isOpen, config])

  async function loadConfig() {
    setLoading(true)
    try {
      const res = await fetch('/api/settings/status')
      const data = await res.json()
      if (res.ok) {
        setConfig(data)
      }
    } catch (err) {
      console.error('Failed to load config', err)
    } finally {
      setLoading(false)
    }
  }

  async function handleTestEmail() {
    setTestingEmail(true)
    setEmailResult(null)
    try {
      const res = await fetch('/api/settings/status', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'test_email' }),
      })
      const data = await res.json()
      setEmailResult(data)
    } catch (err: any) {
      setEmailResult({ success: false, message: err.message ?? 'Test failed' })
    } finally {
      setTestingEmail(false)
    }
  }

  async function handleTestRazorpay() {
    setTestingRazorpay(true)
    setRazorpayResult(null)
    try {
      const res = await fetch('/api/settings/status', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ action: 'test_razorpay' }),
      })
      const data = await res.json()
      setRazorpayResult(data)
    } catch (err: any) {
      setRazorpayResult({ success: false, message: err.message ?? 'Test failed' })
    } finally {
      setTestingRazorpay(false)
    }
  }

  function handleCopy(text: string, key: string) {
    navigator.clipboard.writeText(text)
    setCopiedKey(key)
    setTimeout(() => setCopiedKey(null), 2000)
  }

  return (
    <div className='card' style={{ marginBottom: '24px' }}>
      <div className='card-header' style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <div className='card-title'>🔑 API Configuration</div>
        <button
          id='btn-toggle-api-config'
          className='btn btn-ghost btn-sm'
          onClick={() => setIsOpen(!isOpen)}
        >
          {isOpen ? 'Hide Details ▲' : 'Configure API Keys ▼'}
        </button>
      </div>

      <div className='form-group' style={{ gap: '12px' }}>
        <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>
          Configure, test, and manage your Razorpay payments, Gmail SMTP, and AI services.
        </div>

        {!isOpen ? (
          <button
            id='btn-configure-api-keys'
            className='btn btn-secondary'
            style={{ width: '100%', justifyContent: 'center' }}
            onClick={() => setIsOpen(true)}
          >
            🔑 Configure & Test API Keys
          </button>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: '18px', marginTop: '8px' }}>
            {loading && !config ? (
              <div style={{ textAlign: 'center', padding: '16px', color: 'var(--text-muted)' }}>
                <span className='spinner' /> Loading configuration status...
              </div>
            ) : (
              <>
                {/* Razorpay Section */}
                <div style={{ padding: '14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                    <strong style={{ fontSize: '0.9rem', color: 'var(--text)' }}>💳 Razorpay Payments</strong>
                    <span className={`badge ${config?.razorpay.configured ? 'badge-paid' : 'badge-draft'}`}>
                      {config?.razorpay.configured ? '✅ Active' : '⚠️ Incomplete'}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '8px' }}>
                    Key ID: <code style={{ color: 'var(--text)' }}>{config?.razorpay.keyIdPrefix}</code>
                  </div>

                  <div style={{ marginTop: '10px' }}>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginBottom: '4px', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
                      Webhook Endpoint URL
                    </div>
                    <div style={{ display: 'flex', gap: '8px' }}>
                      <input
                        type='text'
                        readOnly
                        value={config?.razorpay.webhookUrl || ''}
                        className='form-input'
                        style={{ fontSize: '0.8125rem', padding: '6px 10px', flex: 1 }}
                      />
                      <button
                        type='button'
                        className='btn btn-secondary btn-sm'
                        onClick={() => handleCopy(config?.razorpay.webhookUrl || '', 'webhook')}
                      >
                        {copiedKey === 'webhook' ? 'Copied! ✓' : 'Copy'}
                      </button>
                    </div>
                  </div>

                  <div style={{ marginTop: '12px', display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <button
                      type='button'
                      className='btn btn-ghost btn-sm'
                      disabled={testingRazorpay}
                      onClick={handleTestRazorpay}
                      style={{ border: '1px solid var(--border)' }}
                    >
                      {testingRazorpay ? <><span className='spinner' /> Verifying...</> : '⚡ Test Razorpay Connection'}
                    </button>
                    {razorpayResult && (
                      <span style={{ fontSize: '0.8125rem', color: razorpayResult.success ? 'var(--success)' : 'var(--danger)' }}>
                        {razorpayResult.success ? '✓' : '✗'} {razorpayResult.message}
                      </span>
                    )}
                  </div>
                </div>

                {/* Email (Gmail SMTP) Section */}
                <div style={{ padding: '14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '8px' }}>
                    <strong style={{ fontSize: '0.9rem', color: 'var(--text)' }}>✉️ Email (Gmail SMTP)</strong>
                    <span className={`badge ${config?.email.configured ? 'badge-paid' : 'badge-draft'}`}>
                      {config?.email.configured ? '✅ Active' : '⚠️ Incomplete'}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)', marginBottom: '8px' }}>
                    Sender: <code style={{ color: 'var(--text)' }}>{config?.email.sender}</code> ({config?.email.service})
                  </div>

                  <div style={{ marginTop: '12px', display: 'flex', alignItems: 'center', gap: '10px' }}>
                    <button
                      type='button'
                      className='btn btn-ghost btn-sm'
                      disabled={testingEmail}
                      onClick={handleTestEmail}
                      style={{ border: '1px solid var(--border)' }}
                    >
                      {testingEmail ? <><span className='spinner' /> Verifying...</> : '⚡ Test Email Connection'}
                    </button>
                    {emailResult && (
                      <span style={{ fontSize: '0.8125rem', color: emailResult.success ? 'var(--success)' : 'var(--danger)' }}>
                        {emailResult.success ? '✓' : '✗'} {emailResult.message}
                      </span>
                    )}
                  </div>
                </div>

                {/* Gemini AI & Background Jobs */}
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px' }}>
                  <div style={{ padding: '12px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                    <div style={{ fontWeight: 600, fontSize: '0.85rem', marginBottom: '4px' }}>🤖 Gemini AI Agent</div>
                    <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>{config?.ai.model}</div>
                    <span className='badge badge-paid' style={{ marginTop: '6px' }}>Ready</span>
                  </div>

                  <div style={{ padding: '12px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', border: '1px solid var(--border)' }}>
                    <div style={{ fontWeight: 600, fontSize: '0.85rem', marginBottom: '4px' }}>⏰ Upstash QStash</div>
                    <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>Cron Reminders (12h)</div>
                    <span className='badge badge-paid' style={{ marginTop: '6px' }}>Active</span>
                  </div>
                </div>

                {/* Helpful Instruction Note */}
                <div className='alert alert-info' style={{ fontSize: '0.8125rem', padding: '10px 14px' }}>
                  <div>
                    💡 <strong>Environment Variables:</strong> Production credentials are set in <code>.env.local</code> and Vercel project environment variables (<code>RAZORPAY_KEY_ID</code>, <code>GMAIL_USER</code>, <code>GMAIL_APP_PASSWORD</code>, <code>GEMINI_API_KEY</code>).
                  </div>
                </div>
              </>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
