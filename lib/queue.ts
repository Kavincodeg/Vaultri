/**
 * Queue stub — BullMQ replaced by Upstash QStash.
 *
 * Reminders are now triggered automatically by a QStash schedule
 * calling POST /api/cron/check-reminders every 12 hours.
 *
 * These functions are kept as no-ops so that existing import sites
 * (gemini-agent.ts, worker.ts) compile without changes.
 */

/**
 * No-op. Reminder is saved to DB by exec_schedule_reminder;
 * QStash picks it up on the next scheduled run.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export async function enqueueReminder(_reminderId: string, _delayMs: number): Promise<void> {
  // Intentional no-op — QStash cron handles delivery
}

/**
 * No-op. The persistent BullMQ worker process is no longer used.
 * worker.ts calls this but it does nothing.
 */
export async function startReminderWorker(): Promise<null> {
  console.warn('[Worker] BullMQ worker is disabled — reminders are handled by QStash cron.')
  return null
}
