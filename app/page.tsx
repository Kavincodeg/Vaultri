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
      <div className='hero-eyebrow'>🛡️ Suraksha ? Business Protection for Independent Sellers</div>
      <h1 className='hero-title'>Turn every custom order into <span>a protected deal</span></h1>
      <p className='hero-sub'>
        AI-powered deal terms, deposit payment links, automated reminders, and cancellation protection ?
        built specifically for artisan makers, tailors, bakers, and custom creators in India.
      </p>
      <div style={{ display: 'flex', gap: '16px' }}>
        <Link href='/auth/signup' className='btn btn-primary' style={{ fontSize: '1rem', padding: '14px 32px' }}>
          Get Protected Now →
        </Link>
        <Link href='/auth/signin' className='btn btn-secondary' style={{ fontSize: '1rem', padding: '14px 28px' }}>
          Seller Sign In
        </Link>
      </div>
      <div className='fm-grid'>
        {[
          { icon: '❌', title: 'Customer cancels after completion', desc: 'Generate a cancellation-fee payment link + drafted polite message instantly.' },
          { icon: '🎨', title: 'Design copied elsewhere', desc: 'AI-drafted deal terms include explicit IP and design ownership rights.' },
          { icon: '⏳', title: 'Paid 60+ days late', desc: 'Automatic late-payment terms and follow-up payment reminders.' },
          { icon: '📄', title: 'No written record of terms', desc: 'Immutable audit trail and structured agreement text for every deal.' },
        ].map((fm) => (
          <div key={fm.title} className='fm-card'>
            <div className='fm-icon'>{fm.icon}</div>
            <div className='fm-title'>{fm.title}</div>
            <div className='fm-desc'>{fm.desc}</div>
          </div>
        ))}
      </div>
      <blockquote className='hero-quote'>
        “I design and sell jewellery. My pieces take 3 days to make. I have had customers cancel after the piece is finished, copy my designs, and pay me 60 days late. Suraksha gives me the deposit and contract protection every real business needs.”
        <cite>— Artisan Seller Quote</cite>
      </blockquote>
    </div>
  )
}