'use client'
import { useSession } from 'next-auth/react'
import { useRouter } from 'next/navigation'
import { useEffect } from 'react'
import Link from 'next/link'

export default function HomePage() {
  const { status } = useSession()
  const router = useRouter()

  useEffect(() => {
    if (status === 'authenticated') router.push('/dashboard')
  }, [status, router])

  return (
    <div className='hero'>
      <div className='hero-eyebrow'>🛡️ VAULTRI — Business Protection for Independent Sellers</div>
      <h1 className='hero-title'>Turn every custom order into <span>a protected deal</span></h1>
      <p className='hero-sub'>
        AI-powered contract terms, secured deposits, automatic reminders, and cancellation protection — 
        built for artisan makers, jewellers, tailors, bakers, and freelance creators in India.
      </p>
      <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <Link href='/auth/signup' className='btn btn-primary' style={{ fontSize: '1rem', padding: '14px 32px' }}>
          Get Protected Now →
        </Link>
        <Link href='/auth/signin' className='btn btn-secondary' style={{ fontSize: '1rem', padding: '14px 28px' }}>
          Seller Sign In
        </Link>
      </div>
      <div className='fm-grid'>
        {[
          { 
            icon: '❌', 
            title: 'Customer cancels after work completes', 
            desc: 'Generate a cancellation-fee payment link + AI-drafted polite message instantly. Protect your time and effort.' 
          },
          { 
            icon: '🎨', 
            title: 'Design gets copied elsewhere', 
            desc: 'AI-drafted contracts include explicit IP and design ownership clauses to protect your creative work.' 
          },
          { 
            icon: '⏳', 
            title: 'Customers pay 60+ days late', 
            desc: 'Built-in late-payment terms and automatic reminder emails to keep payment flowing on time.' 
          },
          { 
            icon: '📄', 
            title: 'No written agreement exists', 
            desc: 'Immutable audit trail and structured contract for every deal — full legal record in seconds.' 
          },
        ].map((fm) => (
          <div key={fm.title} className='fm-card'>
            <div className='fm-icon'>{fm.icon}</div>
            <div className='fm-title'>{fm.title}</div>
            <div className='fm-desc'>{fm.desc}</div>
          </div>
        ))}
      </div>

      {/* How it works */}
      <div style={{ marginTop: '64px', textAlign: 'center', maxWidth: '700px', margin: '64px auto' }}>
        <h2 style={{ fontSize: '1.75rem', fontWeight: 700, marginBottom: '32px', color: 'var(--text)' }}>
          How It Works in 60 Seconds
        </h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: '24px' }}>
          {[
            { step: '1️⃣', label: 'Describe', desc: 'Tell us about your deal in plain language' },
            { step: '2️⃣', label: 'AI Drafts', desc: 'Contract terms, deposit %, IP clauses auto-generated' },
            { step: '3️⃣', label: 'Get Paid', desc: 'Share deposit link, get paid upfront, send reminders' },
          ].map((item, idx) => (
            <div key={idx} style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: '12px' }}>
              <div style={{ fontSize: '2.5rem' }}>{item.step}</div>
              <div style={{ fontWeight: 600, color: 'var(--text)' }}>{item.label}</div>
              <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>{item.desc}</div>
            </div>
          ))}
        </div>
      </div>

      {/* Key Features */}
      <div style={{ marginTop: '48px', textAlign: 'center', maxWidth: '800px', margin: '48px auto' }}>
        <h2 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: '24px', color: 'var(--text)' }}>
          Why Sellers Love VAULTRI
        </h2>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px' }}>
          {[
            '✅ No more disputes — written agreement for every deal',
            '✅ Deposits collected via Razorpay — 100% secure',
            '✅ AI-generated contracts — no legal fees needed',
            '✅ Automatic reminders — get paid on time, every time',
            '✅ IP protection built-in — designs stay yours',
            '✅ Completely free to start — forever free tier',
          ].map((feature, idx) => (
            <div key={idx} style={{ fontSize: '0.9375rem', color: 'var(--text-muted)' }}>
              {feature}
            </div>
          ))}
        </div>
      </div>

      {/* Testimonial */}
      <blockquote className='hero-quote'>
        "I design and sell jewellery. My pieces take 3 days to make. I've had customers cancel after the piece is finished, copy my designs, and pay me 60 days late. VAULTRI gives me the deposit and contract protection every real business needs."
        <cite>— Priya, Jewellery Designer & VAULTRI Seller</cite>
      </blockquote>

      {/* CTA */}
      <div style={{ marginTop: '48px', textAlign: 'center' }}>
        <div style={{ marginBottom: '16px', color: 'var(--text-muted)' }}>
          Join 500+ sellers protecting their business
        </div>
        <Link href='/auth/signup' className='btn btn-primary' style={{ fontSize: '1rem', padding: '14px 32px' }}>
          Start Protecting Deals Free →
        </Link>
      </div>
    </div>
  )
}