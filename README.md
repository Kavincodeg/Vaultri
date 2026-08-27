# Vaultri — Every deal, protected.

![Vaultri demo — create a deal, see AI-drafted terms, collect deposit](./docs/demo.gif)

A business-protection tool for independent and artisan sellers (jewellers, tailors, bakers, freelance designers). Sellers describe a deal in plain language; the product generates contract terms, collects an upfront deposit via Razorpay, tracks payment status, and follows up with reminders.

> "Turn every custom order into a protected deal in under a minute — no contract-writing, no chasing payments manually."

---

## Tech Stack

| Layer | Choice |
|---|---|
| Framework | Next.js 14 (App Router, TypeScript) |
| Database | PostgreSQL via Neon (Prisma ORM) — free tier |
| Auth | NextAuth v4 — credentials (email + bcrypt) |
| AI Agent | Gemini 2.0 Flash — function calling, server-side only |
| Payments | Razorpay Payment Links API + signed webhooks |
| Background Jobs | Upstash QStash — signed scheduled HTTP calls, no persistent worker |
| Email | Resend |
| Rate Limiting | @upstash/ratelimit (edge middleware) |
| Error Monitoring | Sentry |
| Hosting | Vercel (free tier) |

---

## Getting Started

### 1. Install dependencies

```bash
npm install
```

### 2. Configure environment

Fill in `.env.local` with your values (see existing file for all keys).

### 3. Run database migrations

```bash
npx prisma migrate dev
```

### 4. Seed demo data (optional)

```bash
npx prisma db seed
```

### 5. Start the app

```bash
npm run dev
```

Reminders are triggered **automatically** by Upstash QStash on a schedule — no background worker needed.

> **To enable automatic reminders in production:** add `QSTASH_CURRENT_SIGNING_KEY` and `QSTASH_NEXT_SIGNING_KEY` to Vercel env vars, then create a QStash schedule in the [Upstash console](https://console.upstash.com) pointing to `https://vaultri.vercel.app/api/cron/check-reminders` on a `0 */12 * * *` cron (every 12 hours).

---

## Reminder Flow (QStash Cron)

1. Create a deal → AI drafts contract + Razorpay deposit link + saves Reminder to DB with `status = scheduled`
2. Every 12 hours, Upstash QStash calls `POST /api/cron/check-reminders` (signed request)
3. The route finds all `scheduled` reminders where `deal.dueDate` is within 3 days
4. Calls `exec_send_reminder` for each → email fires via Resend → status updates to `sent`
5. "Send Reminder Now" button on the deal page still works for manual one-off sends

No worker process, no Render, fully serverless.

---

## Architecture

```
Seller (Web App)
      │
      ▼
 Next.js API + Gemini Agent
 (tool-calling, server-side only)
      │
      ├─────────────────────────────────────┐
      ▼              ▼              ▼        ▼
 Postgres DB    Razorpay API    Resend    Upstash QStash
                                         (cron → /api/cron/
                                          check-reminders)
```

### Agent tools (fixed allowlist)

| Tool | What it does |
|---|---|
| `draft_contract` | Generates contract terms with deposit %, cancellation policy, IP + late-payment clauses |
| `create_deposit_link` | Creates a Razorpay Payment Link for the deposit |
| `create_cancellation_fee_link` | Creates a Razorpay Payment Link for the cancellation fee |
| `schedule_reminder` | Saves reminder to DB (+ optionally enqueues BullMQ job) |
| `send_reminder` | Sends email via Resend, updates reminder status |

Every tool call is written to `AuditLog`. The agent never contacts the customer directly.

---

## Development Roadmap

| Phase | Status |
|---|---|
| Phase 0 — Scaffold + docs | ✅ Done |
| Phase 1 — Auth, deal creation, webhook, dashboard | ✅ Done |
| Phase 2 — Reminders, cancellation flow, audit log, rate limiting | ✅ Done |
| Phase 3 — Sentry, CI/CD, Vercel deploy, live mode guide | ✅ Done |
| Phase 4 — WhatsApp reminders, analytics, billing | 📋 Planned |

---

## Free Infrastructure Used

| Service | Purpose | Tier |
|---|---|---|
| Vercel | Next.js hosting | Free |
| Neon | PostgreSQL database | Free |
| Upstash | Redis (rate limiting) | Free |
| Resend | Transactional email | Free (100 emails/day) |
| Sentry | Error monitoring | Free |
| Razorpay | Payments | Test mode (free) |

---

## Legal Note

Vaultri-drafted contracts are plain-language deal terms, not legally reviewed documents. Every generated contract includes a disclaimer footer. See `LIVE_MODE.md` for compliance notes before going live with real payments.
