/**
 * Suraksha — Reminder Worker
 *
 * Run this as a separate process alongside the Next.js app:
 *   npm run worker
 *
 * In production, deploy this on a separate always-on dyno (Render, Railway,
 * or Fly.io) since Vercel serverless functions can't run persistent workers.
 *
 * Doc ref: Section 5 — BullMQ + Redis for background jobs with retries.
 */
import * as dotenv from 'dotenv'
dotenv.config({ path: '.env.local' })
dotenv.config()

import { startReminderWorker } from './lib/queue'

async function main() {
  const worker = await startReminderWorker()

  if (!worker) {
    console.error('[Worker] Could not start — REDIS_URL not configured. Set REDIS_URL in .env.local')
    process.exit(1)
  }

  console.log('[Worker] Reminder worker started and listening for jobs...')

  const shutdown = async () => {
    console.log('[Worker] Shutting down gracefully...')
    await worker.close()
    process.exit(0)
  }

  process.on('SIGTERM', shutdown)
  process.on('SIGINT', shutdown)
}

main()
