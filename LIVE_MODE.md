# Switching to Razorpay Live Mode

Follow this checklist before onboarding real sellers.

## 1. Complete Razorpay KYC

1. Log in to [dashboard.razorpay.com](https://dashboard.razorpay.com)
2. Go to **Account & Settings → Business Profile**
3. Submit KYC documents:
   - PAN card (personal or business)
   - Bank account details for settlements
   - Business proof (GST certificate, shop registration, or similar)
4. Wait for approval — typically 1–3 business days

## 2. Activate Payment Links

1. In Razorpay Dashboard → **Payment Links** → confirm the feature is enabled for your account
2. Test a live payment link manually before going live

## 3. Swap environment variables

Replace test keys with live keys in your Vercel project settings (never in `.env` files):

```
RAZORPAY_KEY_ID=rzp_live_XXXXXXXXXX        # from API Keys section
RAZORPAY_KEY_SECRET=XXXXXXXXXX
RAZORPAY_WEBHOOK_SECRET=XXXXXXXXXX         # set a new strong secret
```

## 4. Register the Webhook in Razorpay Dashboard

1. Go to **Account & Settings → Webhooks → Add New Webhook**
2. URL: `https://your-domain.vercel.app/api/webhook`
3. Secret: same value as `RAZORPAY_WEBHOOK_SECRET` in your env
4. Events to subscribe:
   - `payment_link.paid`
5. Save and note the webhook ID

## 5. Verify webhook signature enforcement

In `app/api/webhook/route.ts`, the signature check is already active when `RAZORPAY_WEBHOOK_SECRET` is set. Confirm the env var is populated in production — Sentry will alert on any signature mismatches.

## 6. Update NEXTAUTH_URL

Set `NEXTAUTH_URL` in Vercel to your actual production domain:
```
NEXTAUTH_URL=https://your-domain.vercel.app
```

## 7. Pre-launch checklist

- [ ] Razorpay KYC approved
- [ ] Live API keys set in Vercel environment variables
- [ ] Webhook registered and verified in Razorpay dashboard
- [ ] Sentry DSN set — test by triggering a test error
- [ ] Upstash Redis connected — test by creating a deal and checking BullMQ job appears
- [ ] Send a test deal end-to-end: create → deposit link → pay → status updates → reminder fires
- [ ] Privacy policy page live (required before storing customer data)
