# Vaultri — Every deal, protected.

![Vaultri demo — create a deal, see AI-drafted terms, collect deposit](./docs/demo.gif)

A business-protection tool for independent and artisan sellers (jewellers, tailors, bakers, freelance designers). Sellers describe a deal in plain language; the product generates contract terms, collects an upfront deposit via Razorpay, tracks payment status, and follows up with automated reminders.

> "Turn every custom order into a protected deal in under a minute — no contract-writing, no chasing payments manually."

---

## 🚀 Tech Stack

| Layer | Choice | Notes |
|---|---|---|
| **Framework** | Next.js 14 (App Router, TypeScript) | Server components, API routes, Edge middleware |
| **Styling & UI** | Vanilla CSS + Design System | Custom themes (Light & Dark mode), CSS variables |
| **Database** | PostgreSQL via Neon (Prisma ORM) | Serverless PostgreSQL database |
| **Auth** | NextAuth v4 | Credentials provider (Email + Bcrypt password hash) |
| **AI Agent** | Gemini 2.5 Flash + Resilient Fallback | Tool-calling server-side with fallback guardrails |
| **Payments** | Razorpay Payment Links API + Webhooks | Signed webhooks (`/api/webhook`) for instant status updates |
| **Background Jobs** | Upstash QStash | Signed scheduled HTTP calls (`0 */12 * * *`), zero persistent workers |
| **Email** | Nodemailer + Gmail SMTP | Transactional reminders, free, no custom domain required |
| **Rate Limiting** | `@upstash/ratelimit` + Upstash Redis | Edge middleware API protection |
| **Monitoring** | Sentry (`@sentry/nextjs`) | Server, client, and edge error tracking |
| **Hosting & CI/CD**| Vercel + GitHub Actions | Automated build, lint & deploy on push to `main` |

---

## ✨ Key Features

- **🤖 AI Deal Protection & Contract Generation:** Plain language input automatically converts into plain-English contracts with customized deposit %, 50% cancellation fee, intellectual property clauses, and late payment terms.
- **💳 Razorpay Payment Links:** Automated creation of deposit and cancellation fee payment links with real-time webhook sync.
- **✉️ Smart Reminder Delivery:**
  - Enter an optional customer email on deal creation to send reminders directly to the customer.
  - Automatically falls back to the seller's account email if omitted.
  - Scheduled automatically 2 days before the due date via QStash cron, plus one-click "Send Reminder Now" on deal pages.
- **🛡️ Deal Protection Score:** Interactive 0–100 score analyzing contract drafting, deposit security, IP terms, and active reminders.
- **🎨 Theme Support:** Full light and dark mode support with instant toggle and settings selector.
- **🔑 Interactive API Configuration & Diagnostics:** Settings page with one-click live connection testing for Gmail SMTP and Razorpay, plus copyable Webhook and Cron endpoints.
- **📜 Immutable Audit Trail:** Every deal action (creation, contracts, payment links, reminder dispatches) is permanently recorded.

---

## 🛠️ Getting Started

### 1. Clone & install dependencies

```bash
git clone https://github.com/Kavincodeg/Vaultri.git
cd Vaultri/seller-protection
npm install
```

### 2. Configure environment variables

Create a `.env.local` file in the root directory and populate the required keys:

```env
# ─── Database ───────────────────────────────────────────────────
DATABASE_URL="postgresql://user:password@ep-xyz.aws.neon.tech/neondb?sslmode=require"

# ─── NextAuth ───────────────────────────────────────────────────
NEXTAUTH_URL="http://localhost:3000"
NEXTAUTH_SECRET="your-generated-nextauth-secret"

# ─── Gmail SMTP (Email Reminders) ──────────────────────────────
GMAIL_USER="yourname@gmail.com"
GMAIL_APP_PASSWORD="xxxx xxxx xxxx xxxx"   # 16-character Google App Password

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

### 3. Generate Prisma Client & Run Migrations

```bash
npx prisma generate
npx prisma migrate dev
```

### 4. Start Development Server

```bash
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) in your browser.

---

## 🔔 Reminder Flow (QStash Cron & Serverless)

```
Deal Created
   │
   ├──> AI Agent drafts contract + generates Razorpay link
   │
   └──> Creates Reminder row in DB (status: 'scheduled')
           │
           ▼ Every 12 Hours (0 */12 * * *)
     Upstash QStash calls POST /api/cron/check-reminders (signed request)
           │
           ▼
     Queries upcoming deals (due within 3 days, not completed/cancelled)
           │
           ▼
     Fires exec_send_reminder() via Nodemailer + Gmail SMTP
           │
           ▼
     Email delivered to Customer (or Seller fallback) ──> Status: 'sent'
```

1. **Automatic Delivery:** Upstash QStash calls `/api/cron/check-reminders` on a `0 */12 * * *` schedule.
2. **Manual Delivery:** Sellers can click **"Send Reminder Now"** directly on any deal page for instant dispatch.
3. **Fully Serverless:** No persistent worker daemon or Render instance required.

---

## 🏗️ System Architecture

```
 Seller (Web App)
       │
       ▼
  Next.js API + Gemini Agent
  (tool-calling, server-side only with fallback engine)
       │
       ├─────────────────────────────────────┐
       ▼              ▼              ▼        ▼
  Postgres DB    Razorpay API    Gmail     Upstash QStash
  (Neon)        (Payment Links, (Nodemailer  (Cron → /api/cron/
                 Webhooks)       SMTP)        check-reminders)
```

### Agent Tool Allowlist

| Tool | Action |
|---|---|
| `draft_contract` | Generates contract terms with deposit %, cancellation policy, IP clause, and late-payment terms |
| `create_deposit_link` | Creates a Razorpay Payment Link for the deposit |
| `create_cancellation_fee_link` | Creates a Razorpay Payment Link for the cancellation fee |
| `schedule_reminder` | Saves reminder record in DB for cron processing |
| `send_reminder` | Dispatches reminder email via Gmail SMTP and updates audit trail |

Every tool execution is logged in the `AuditLog` table. The agent never contacts customers outside allowed functions.

---

## 📦 Free Infrastructure Stack

| Service | Purpose | Tier |
|---|---|---|
| **Vercel** | Next.js Hosting & Serverless Functions | Free Tier |
| **Neon** | Managed PostgreSQL Database | Free Tier |
| **Upstash Redis** | Edge Rate Limiting | Free Tier |
| **Upstash QStash** | Scheduled Cron Calls | Free Tier |
| **Gmail SMTP** | Transactional Email Delivery | Free (via Google App Passwords) |
| **Google Gemini** | AI Agent & Contract Generation | Free Tier API |
| **Razorpay** | Payment Links & Webhooks | Test Mode (Free) |
| **Sentry** | Full-stack Error Monitoring | Free Developer Tier |

---

## ⚖️ Legal Note

Vaultri-drafted contracts are plain-language deal summaries designed to protect independent sellers and establish clear mutual expectations, not legally binding counsel. Every generated contract includes a standard disclaimer footer. See `LIVE_MODE.md` for KYC and compliance notes before switching to live payments.
