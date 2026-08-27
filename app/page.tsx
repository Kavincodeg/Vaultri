'use client'
import { useSession } from 'next-auth/react'
import { useRouter } from 'next/navigation'
import { useEffect } from 'react'
import Link from 'next/link'
import {
  ShieldCheck,
  XCircle,
  Palette,
  Clock,
  FileText,
  CheckCircle2,
  ArrowRight,
  Sparkles,
  Bell,
  CreditCard,
} from 'lucide-react'

/* ─── Problem cards data ─────────────────────────────────── */
const PROBLEMS = [
  {
    Icon: XCircle,
    title: 'Customer cancels after work completes',
    desc: 'Generate a cancellation-fee payment link + AI-drafted polite message instantly. Protect your time and effort.',
  },
  {
    Icon: Palette,
    title: 'Design gets copied elsewhere',
    desc: 'AI-drafted contracts include explicit IP and design ownership clauses to protect your creative work.',
  },
  {
    Icon: Clock,
    title: 'Customers pay 60+ days late',
    desc: 'Built-in late-payment terms and automatic reminder emails to keep payment flowing on time.',
  },
  {
    Icon: FileText,
    title: 'No written agreement exists',
    desc: 'Immutable audit trail and structured contract for every deal — full legal record in seconds.',
  },
]

/* ─── Steps data ─────────────────────────────────────────── */
const STEPS = [
  {
    num: '01',
    Icon: Sparkles,
    label: 'Describe',
    desc: 'Tell us about your deal in plain language',
  },
  {
    num: '02',
    Icon: FileText,
    label: 'AI Drafts',
    desc: 'Contract terms, deposit %, IP clauses auto-generated',
  },
  {
    num: '03',
    Icon: CreditCard,
    label: 'Get Paid',
    desc: 'Share deposit link, get paid upfront, send reminders',
  },
]

/* ─── Features list ──────────────────────────────────────── */
const FEATURES = [
  'No more disputes — written agreement for every deal',
  'Deposits collected via Razorpay — 100% secure',
  'AI-generated contracts — no legal fees needed',
  'Automatic reminders — get paid on time, every time',
  'IP protection built-in — designs stay yours',
  'Completely free to start — forever free tier',
]

export default function HomePage() {
  const { status } = useSession()
  const router = useRouter()

  useEffect(() => {
    if (status === 'authenticated') router.push('/dashboard')
  }, [status, router])

  return (
    <div className='hero'>

      {/* ── Eyebrow badge ─────────────────────────────────── */}
      <div className='hero-eyebrow'>
        <ShieldCheck size={14} strokeWidth={2.5} />
        VAULTRI — Business Protection for Independent Sellers
      </div>

      {/* ── Headline ──────────────────────────────────────── */}
      <h1 className='hero-title'>
        Turn every custom order into <span>a protected deal</span>
      </h1>

      <p className='hero-sub'>
        AI-powered contract terms, secured deposits, automatic reminders, and cancellation protection —
        built for artisan makers, jewellers, tailors, bakers, and freelance creators in India.
      </p>

      {/* ── CTA row ───────────────────────────────────────── */}
      <div style={{ display: 'flex', gap: '16px', flexWrap: 'wrap', justifyContent: 'center' }}>
        <Link href='/auth/signup' className='btn btn-primary' style={{ fontSize: '1rem', padding: '14px 32px', gap: '10px' }}>
          Get Protected Now <ArrowRight size={16} />
        </Link>
        <Link href='/auth/signin' className='btn btn-secondary' style={{ fontSize: '1rem', padding: '14px 28px' }}>
          Seller Sign In
        </Link>
      </div>

      {/* ── Problem cards ─────────────────────────────────── */}
      <div className='fm-grid'>
        {PROBLEMS.map(({ Icon, title, desc }) => (
          <div key={title} className='fm-card'>
            <div className='fm-icon'>
              <Icon size={22} strokeWidth={1.75} color='var(--accent)' />
            </div>
            <div className='fm-title'>{title}</div>
            <div className='fm-desc'>{desc}</div>
          </div>
        ))}
      </div>

      {/* ── How it works ──────────────────────────────────── */}
      <div className='landing-section'>
        <h2 className='landing-section-title'>How It Works in 60 Seconds</h2>
        <div className='steps-grid'>
          {STEPS.map(({ num, Icon, label, desc }) => (
            <div key={num} className='step-card'>
              <div className='step-num'>{num}</div>
              <div className='step-icon-wrap'>
                <Icon size={24} strokeWidth={1.75} color='var(--accent)' />
              </div>
              <div className='step-label'>{label}</div>
              <div className='step-desc'>{desc}</div>
            </div>
          ))}
        </div>
      </div>

      {/* ── Feature list ──────────────────────────────────── */}
      <div className='landing-section'>
        <h2 className='landing-section-title'>Why Sellers Choose Vaultri</h2>
        <div className='features-grid'>
          {FEATURES.map((feature) => (
            <div key={feature} className='feature-row'>
              <CheckCircle2 size={16} strokeWidth={2} color='var(--accent)' style={{ flexShrink: 0, marginTop: '2px' }} />
              <span style={{ fontSize: '0.9375rem', color: 'var(--text-muted)' }}>{feature}</span>
            </div>
          ))}
        </div>
      </div>

      {/* ── Problem pull-quote (inspiration, not a user quote) ── */}
      <div className='hero-quote' style={{ fontStyle: 'normal' }}>
        <div style={{
          fontSize: '0.75rem', textTransform: 'uppercase', letterSpacing: '0.08em',
          color: 'var(--accent)', fontWeight: 600, marginBottom: '12px',
        }}>
          The problem that inspired Vaultri
        </div>
        <p style={{ fontStyle: 'italic', color: 'var(--text-muted)', lineHeight: 1.7 }}>
          &ldquo;Every sale is an act of faith. You spend days making something custom — only for a
          customer to cancel, copy your design, or pay 60 days late. Independent sellers need the
          same protection that big businesses take for granted.&rdquo;
        </p>
      </div>

      {/* ── Bottom CTA ────────────────────────────────────── */}
      <div style={{ marginTop: '48px', textAlign: 'center' }}>
        <div style={{ marginBottom: '16px', color: 'var(--text-muted)', fontSize: '0.9375rem' }}>
          Built to solve a real problem independent sellers face
        </div>
        <Link href='/auth/signup' className='btn btn-primary' style={{ fontSize: '1rem', padding: '14px 32px', gap: '10px' }}>
          Start Protecting Deals Free <ArrowRight size={16} />
        </Link>
      </div>

    </div>
  )
}