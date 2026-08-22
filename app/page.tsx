"use client"
import { useSession, signIn } from "next-auth/react"
import { useRouter } from "next/navigation"
import { useEffect } from "react"

export default function HomePage() {
  const { data: session, status } = useSession()
  const router = useRouter()

  useEffect(() => {
    if (status === "authenticated") router.push("/dashboard")
  }, [status, router])

  return (
    <div className="hero">
      <div className="hero-eyebrow">
        <span>🤖</span> Razorpay AI Buildathon — Open Track
      </div>
      <h1 className="hero-title">
        Every sale shouldn&apos;t be <span>an act of faith</span>
      </h1>
      <p className="hero-sub">
        AI-powered contract drafting, deposit collection, and cancellation protection
        — built for independent sellers who deserve the same safeguards as any business.
      </p>
      <button
        id="btn-get-started"
        className="btn btn-primary"
        style={{ fontSize: "1rem", padding: "14px 32px" }}
        onClick={() => signIn()}
      >
        Get Protected →
      </button>

      <div className="fm-grid">
        {[
          { icon: "❌", title: "Customer cancels last minute", desc: "Automatic cancellation-fee payment link generated instantly." },
          { icon: "🎨", title: "Design copied elsewhere", desc: "Contract terms include IP/design clause drafted by AI." },
          { icon: "⏳", title: "Paid 60 days late", desc: "Late-payment clause in every contract. Reminder flow built in." },
          { icon: "📄", title: "No written record", desc: "Every deal stores agent-drafted contract text in database." },
        ].map((fm) => (
          <div key={fm.title} className="fm-card">
            <div className="fm-icon">{fm.icon}</div>
            <div className="fm-title">{fm.title}</div>
            <div className="fm-desc">{fm.desc}</div>
          </div>
        ))}
      </div>

      <blockquote className="hero-quote">
        &ldquo;I design and sell jewellery. My pieces take 3 days to make. I&apos;ve had customers cancel
        after the piece is finished, copy my designs and get them made cheaper elsewhere, and pay
        me 60 days late. I have no contract, no deposit system, no protection. Every sale is an
        act of faith — and sometimes faith is expensive.&rdquo;
        <cite>— PS 30, Independent Jewellery Maker</cite>
      </blockquote>
    </div>
  )
}
