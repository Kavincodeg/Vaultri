/**
 * QStash-triggered cron endpoint — checks for due reminders and sends them.
 *
 * QStash calls this route on a schedule (e.g. every 12 h).
 * The request is signed with the QStash signing key; we reject anything
 * that doesn't pass verification to prevent spoofed trigger requests.
 *
 * Set up in the Upstash QStash console:
 *   URL:      https://vaultri.vercel.app/api/cron/check-reminders
 *   Schedule: 0 *\/12 * * *  (every 12 hours)
 *   Method:   POST
 *
 * Required env vars (add to Vercel + .env.local):
 *   QSTASH_CURRENT_SIGNING_KEY
 *   QSTASH_NEXT_SIGNING_KEY
 */
import { NextResponse } from 'next/server'
import { verifySignatureAppRouter } from '@upstash/qstash/nextjs'
import { prisma } from '@/lib/prisma'
import { exec_send_reminder } from '@/lib/gemini-agent'
import * as Sentry from '@sentry/nextjs'

// Window: send reminders for deals due within the next N days
const DUE_WINDOW_DAYS = 3

async function handler() {
  const now = new Date()
  const windowEnd = new Date(now)
  windowEnd.setDate(windowEnd.getDate() + DUE_WINDOW_DAYS)

  // Find all scheduled reminders whose deal dueDate falls within the window
  const due = await prisma.reminder.findMany({
    where: {
      status: 'scheduled',
      deal: {
        dueDate: {
          gte: now,        // not already past due
          lte: windowEnd,  // within 3-day window
        },
      },
    },
    include: { deal: true },
  })

  const results = { processed: due.length, sent: 0, failed: 0, ids: [] as string[] }

  for (const reminder of due) {
    try {
      await exec_send_reminder({ reminderId: reminder.id })
      results.sent++
      results.ids.push(reminder.id)
    } catch (err: any) {
      results.failed++
      console.error('[cron/check-reminders] Failed for reminder', reminder.id, err?.message)
      Sentry.captureException(err, {
        tags: { context: 'qstash_cron_reminder' },
        extra: { reminderId: reminder.id, dealId: reminder.dealId },
      })
    }
  }

  console.log('[cron/check-reminders]', JSON.stringify(results))
  return NextResponse.json(results)
}

// Wrapping with verifySignatureAppRouter rejects any request that is not
// signed by QStash — protects against unauthorised trigger attempts.
export const POST = verifySignatureAppRouter(handler)
