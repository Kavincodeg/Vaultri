<div align="center">

# 🛡️ Vaultri

### Turn every custom order into a protected deal — in under a minute.

[![Live Demo](https://img.shields.io/badge/Live-vaultri.vercel.app-C9A227?style=for-the-badge)](https://vaultri.vercel.app)
[![Watch Demo](https://img.shields.io/badge/Watch-Loom%20Walkthrough-8B5CF6?style=for-the-badge&logo=loom)](https://www.loom.com/share/6eb00be78ee64e3689177eb9c2070865)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg?style=for-the-badge)](LICENSE)
[![Built with Next.js](https://img.shields.io/badge/Built%20with-Next.js%2014-black?style=for-the-badge&logo=next.js)](https://nextjs.org)

[Live Demo](https://vaultri.vercel.app) · [🎥 Watch the Loom Walkthrough](https://www.loom.com/share/6eb00be78ee64e3689177eb9c2070865) · [Report a Bug](../../issues)

</div>

---

![Vaultri demo — create a deal, see AI-drafted terms, collect deposit](./docs/demo.gif)
*Creating a deal, generating a deposit link, and watching payment status update — live on Razorpay test mode.*

🎥 **[Watch the 2-minute walkthrough on Loom →](https://www.loom.com/share/6eb00be78ee64e3689177eb9c2070865)**

---

## The Problem

> "I design and sell jewellery. My pieces take 3 days to make. I've had customers cancel after the piece is finished, copy my designs and get them made cheaper elsewhere, and pay me 60 days late. I have no contract, no deposit system, no protection. Every sale is an act of faith — and sometimes faith is expensive."

Independent sellers of custom-made goods — jewellers, tailors, bakers, freelance designers — take on real business risk (materials, time, cash flow) with none of the protections a registered business has by default: no written terms, no upfront deposit, no cancellation policy, no paper trail. These tools exist in the corporate world but have never been packaged simply enough for a one-person seller working over WhatsApp or Instagram.

**The result:** lost income to last-minute cancellations, copied designs sold cheaper elsewhere, and payments delayed months with no leverage to prevent it.

## The Solution

Vaultri turns a plain-language deal description into a protected transaction automatically:

1. **Describe the deal** — "Priya wants a custom necklace, ₹8,000, needs it by Aug 30"
2. **AI drafts the terms** — deposit %, cancellation policy, IP/design clauses, late-payment terms
3. **A real deposit link is generated** — via Razorpay Payment Links, shareable over WhatsApp in one tap
4. **Payment status updates automatically** — signed, idempotent Razorpay webhooks
5. **Reminders and cancellation-fee flows run on schedule** — without the seller writing a single follow-up message

---

## Key Decisions & Trade-offs

*The reasoning behind a few deliberate choices — including what I chose not to do, and why.*

- **AI-drafted terms are explicitly not legally binding contracts.** Every generated contract includes a plain-language disclaimer footer. This is a protection layer, not a legal product — going further would need legal review this project doesn't have, and overstating legal weight would be dishonest to the seller relying on it.
- **The agent works from a fixed tool allowlist, not free-form generation.** `draft_contract`, `create_deposit_link`, `schedule_reminder`, and similar tools are the only actions the AI can take, and every call is written to an immutable audit log. In a product that touches real payments, predictability and auditability mattered more than flexibility.
- **Reminders run without a persistent worker process.** Rather than hosting a 24/7 BullMQ worker (which needs paid or unreliable free-tier hosting), reminders are handled by Upstash QStash on a scheduled HTTP call. This keeps the entire stack on free infrastructure without sacrificing reliability, at the cost of reminder granularity (every 12 hours, not real-time).
- **Gmail SMTP over a transactional email provider like Resend.** Providers like Resend restrict delivery to verified domains on their free tier — without owning a domain, that means emails can only reach a test inbox, not real customers. Gmail SMTP trades a fully branded sender address for something that actually works today, for free.
- **Manual "Send Reminder Now" alongside the automated cron.** Automated reminders build trust over time, but a seller who wants to nudge a customer *right now* shouldn't have to wait for the next scheduled run — so both paths exist.

---

## Features

- ✅ **AI Deal Protection & Contract Generation** — plain-language input becomes drafted terms: deposit %, 50% cancellation fee, IP clauses, late-payment terms
- ✅ **Razorpay Payment Links** — automated deposit and cancellation-fee links with real-time webhook sync
- ✅ **Smart Reminder Delivery** — customer email if provided, seller-account fallback if not; scheduled automatically 2 days before due date via QStash, plus one-click manual send
- ✅ **Deal Protection Score** — 0–100 score analyzing contract drafting, deposit security, IP terms, and active reminders
- ✅ **Light & dark theme support** with instant toggle
- ✅ **Interactive API diagnostics** — one-click live connection testing for Gmail SMTP and Razorpay from the settings page
- ✅ **Immutable audit trail** — every deal action permanently recorded
- 📋 *Planned:* WhatsApp reminders, analytics dashboard, billing/subscriptions

---

## Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| **Framework** | Next.js 14 (App Router, TypeScript) | Server components, API routes, Edge middleware |
| **Styling & UI** | Vanilla CSS + design system | Custom light/dark themes, CSS variables |
| **Database** | PostgreSQL via Neon (Prisma ORM) | Serverless PostgreSQL |
| **Auth** | NextAuth v4 | Credentials provider (email + bcrypt hash) |
| **AI Agent** | Gemini 2.5 Flash + resilient fallback | Tool-calling, server-side only, fallback guardrails |
| **Payments** | Razorpay Payment Links API + webhooks | Signed webhooks (`/api/webhook`) for instant status updates |
| **Background Jobs** | Upstash QStash | Signed scheduled HTTP calls (`0 */12 * * *`), zero persistent workers |
| **Email** | Nodemailer + Gmail SMTP | Transactional reminders, free, no custom domain required |
| **Rate Limiting** | `@upstash/ratelimit` + Upstash Redis | Edge middleware API protection |
| **Monitoring** | Sentry (`@sentry/nextjs`) | Server, client, and edge error tracking |
| **Hosting & CI/CD** | Vercel + GitHub Actions | Automated build, lint & deploy on push to `main` |

---

## System Architecture

```
 Seller (Web App)
       │
       ▼
  Next.js API + Gemini Agent
  (tool-calling, server-side only, with fallback engine)
       │
       ├─────────────────────────────────────┐
       ▼              ▼              ▼        ▼
  Postgres DB    Razorpay API    Gmail SMTP   Upstash QStash
  (Neon)        (Payment Links,  (Nodemailer)  (Cron → /api/cron/
                 Webhooks)                      check-reminders)
```

### Agent Tool Allowlist

| Tool | Action |
|---|---|
| `draft_contract` | Generates contract terms — deposit %, cancellation policy, IP clause, late-payment terms |
| `create_deposit_link` | Creates a Razorpay Payment Link for the deposit |
| `create_cancellation_fee_link` | Creates a Razorpay Payment Link for the cancellation fee |
| `schedule_reminder` | Saves a reminder record for cron processing |
| `send_reminder` | Dispatches the reminder email via Gmail SMTP, updates the audit trail |

Every tool execution is logged in the `AuditLog` table. The agent never contacts a customer outside these allowed functions.

## Reminder Flow

```
Deal Created
   │
   ├──> AI agent drafts contract + generates Razorpay link
   │
   └──> Creates Reminder row in DB (status: 'scheduled')
           │
           ▼ Every 12 hours (0 */12 * * *)
     Upstash QStash calls POST /api/cron/check-reminders (signed request)
           │
           ▼
     Queries upcoming deals (due within 3 days, not completed/cancelled)
           │
           ▼
     Dispatches reminder via Nodemailer + Gmail SMTP
           │
           ▼
     Email delivered to customer (or seller fallback) ──> status: 'sent'
```

Sellers can also click **"Send Reminder Now"** on any deal page for an instant, on-demand dispatch — no worker daemon or persistent hosting required either way.

---

## Getting Started

### 1. Clone & install dependencies

```bash
git clone https://github.com/Kavincodeg/Vaultri.git
cd Vaultri/seller-protection
npm install
```

### 2. Configure environment variables

Create a `.env.local` file in the root directory:

```env
# ─── Database ───────────────────────────────────────────────────
DATABASE_URL="postgresql://user:password@ep-xyz.aws.neon.tech/neondb?sslmode=require"

# ─── NextAuth ───────────────────────────────────────────────────
NEXTAUTH_URL="http://localhost:3000"
NEXTAUTH_SECRET="your-generated-nextauth-secret"

# ─── Gmail SMTP (Email Reminders) ──────────────────────────────
GMAIL_USER="yourname@gmail.com"
GMAIL_APP_PASSWORD="xxxx xxxx xxxx xxxx"

# ─── Google Gemini AI ───────────────────────────────────────────
GEMINI_API_KEY="your-gemini-api-key"

# ─── Razorpay (Test Mode) ───────────────────────────────────────
RAZORPAY_KEY_ID="rzp_test_xxxx"
RAZORPAY_KEY_SECRET="your-razorpay-key-secret"
RAZORPAY_WEBHOOK_SECRET="your-webhook-secret"

# ─── App Configuration ──────────────────────────────────────────
NEXT_PUBLIC_APP_URL="http://localhost:3000"

# ─── Upstash Redis (Rate Limiting) ─────────────────────────────
UPSTASH_REDIS_REST_URL="https://your-instance.upstash.io"
UPSTASH_REDIS_REST_TOKEN="your-upstash-redis-token"

# ─── Upstash QStash (Automated Reminders) ───────────────────────
QSTASH_TOKEN="your-qstash-token"
QSTASH_CURRENT_SIGNING_KEY="sig_xxxx"
QSTASH_NEXT_SIGNING_KEY="sig_xxxx"

# ─── Sentry (Error Monitoring — Optional) ───────────────────────
NEXT_PUBLIC_SENTRY_DSN="https://xxxx@sentry.io/xxxx"
SENTRY_AUTH_TOKEN="sntrys_xxxx"
SENTRY_ORG="your-sentry-org"
SENTRY_PROJECT="vaultri"
```

### 3. Generate Prisma client & run migrations

```bash
npx prisma generate
npx prisma migrate dev
```

### 4. Start the development server

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000).

---

## Free Infrastructure Stack

*Built entirely on free tiers — no paid services required to run or evaluate this project.*

| Service | Purpose | Tier |
|---|---|---|
| Vercel | Hosting & serverless functions | Free |
| Neon | Managed PostgreSQL | Free |
| Upstash Redis | Edge rate limiting | Free |
| Upstash QStash | Scheduled cron calls | Free |
| Gmail SMTP | Transactional email | Free (Google App Passwords) |
| Google Gemini | AI agent & contract generation | Free tier API |
| Razorpay | Payment links & webhooks | Test mode (free) |
| Sentry | Full-stack error monitoring | Free |

---

## Roadmap

| Phase | Status |
|---|---|
| Scaffold, auth, deal creation, webhooks, dashboard | ✅ Done |
| Reminders, cancellation flow, audit log, rate limiting | ✅ Done |
| Monitoring, CI/CD, deploy, live-mode compliance guide | ✅ Done |
| Automated QStash reminder scheduling | ✅ Done |
| WhatsApp reminders, analytics dashboard, billing | 📋 Planned |

---

## Legal Note

Vaultri-drafted contracts are plain-language deal summaries designed to protect independent sellers and establish clear mutual expectations — not legally binding counsel. Every generated contract includes a standard disclaimer footer. See [`LIVE_MODE.md`](./LIVE_MODE.md) for KYC and compliance notes before switching to live payments.

---

## What I'd Do Differently

Right now the entire product is seller-facing — a customer only ever sees a Razorpay checkout page, never a branded confirmation of what they agreed to. With more time I'd build a lightweight customer-facing deal view, so both sides of the transaction have the same record. I'd also move deposit-percentage recommendations from a fixed heuristic to something that accounts for order category and price point — a 40% deposit makes sense on a ₹5,000 order, less obviously so on a ₹50,000 one.

---

<div align="center">

Built by [Kavin v s](https://github.com/Kavincodeg) · [LinkedIn](https://www.linkedin.com/in/kavin-v-s-605940292/)

⭐ If this project was useful or interesting, consider giving it a star.

</div>
