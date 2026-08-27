/**
 * Suraksha — Reminder Worker
 *
 * BullMQ worker is disabled. Reminders are now handled by Upstash QStash cron.
 * This file is retained as a safe no-op.
 */
import * as dotenv from 'dotenv'
dotenv.config({ path: '.env.local' })
dotenv.config()

import { startReminderWorker } from './lib/queue'

async function main() {
  const worker = await startReminderWorker()

  if (!worker) {
    console.log('[Worker] BullMQ worker is disabled — reminders are handled by QStash cron.')
    return
  }
}

main()
