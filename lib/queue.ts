/**
 * BullMQ job queue — reminder delivery with retry logic.
 *
 * bullmq and ioredis are imported lazily inside function bodies so webpack
 * does not try to bundle them (they are Node.js-only, not edge-compatible).
 * next.config.js lists them in serverComponentsExternalPackages for safety.
 *
 * Requires REDIS_URL in .env.
 * For production, use Upstash Redis (set REDIS_URL to the Upstash Redis URL).
 *
 * Doc ref: Section 5 — BullMQ + Redis for background jobs.
 */

const QUEUE_NAME = 'reminders'
const MAX_RETRIES = 3

/**
 * Enqueue a reminder job.
 * @param reminderId  DB Reminder.id
 * @param delayMs     Milliseconds until the job should fire (0 = immediate)
 */
export async function enqueueReminder(reminderId: string, delayMs: number): Promise<void> {
  const url = process.env.REDIS_URL
  if (!url) {
    console.warn('[Queue] REDIS_URL not set — skipping enqueue for reminder', reminderId)
    return
  }

  // Lazy imports — keeps bullmq/ioredis out of webpack's static analysis
  const { Queue } = await import('bullmq')
  const IORedis = (await import('ioredis')).default

  const connection = new IORedis(url, {
    maxRetriesPerRequest: null,
    enableReadyCheck: false,
  })

  const queue = new Queue(QUEUE_NAME, {
    connection,
    defaultJobOptions: {
      attempts: MAX_RETRIES,
      backoff: { type: 'exponential', delay: 60_000 }, // 1min → 2min → 4min
      removeOnComplete: 100,
      removeOnFail: 200,
    },
  })

  await queue.add(
    'send-reminder',
    { reminderId },
    {
      jobId: 'reminder-' + reminderId, // idempotent — same reminder won't be double-queued
      delay: delayMs,
    },
  )

  await queue.close()
  await connection.quit()
}

/**
 * Start the reminder worker.
 * Call this from the standalone worker process (worker.ts), not from API routes.
 */
export async function startReminderWorker() {
  const url = process.env.REDIS_URL
  if (!url) {
    console.error('[Worker] REDIS_URL not set — cannot start worker')
    return null
  }

  const { Worker } = await import('bullmq')
  const IORedis = (await import('ioredis')).default

  const connection = new IORedis(url, {
    maxRetriesPerRequest: null,
    enableReadyCheck: false,
  })

  const worker = new Worker(
    QUEUE_NAME,
    async (job: any) => {
      const { exec_send_reminder } = await import('./gemini-agent')
      const { reminderId } = job.data as { reminderId: string }
      await exec_send_reminder({ reminderId })
    },
    { connection, concurrency: 5 },
  )

  worker.on('completed', (job: any) => {
    console.log('[Worker] Reminder sent:', job.data.reminderId)
  })

  worker.on('failed', (job: any, err: any) => {
    console.error('[Worker] Reminder failed:', job?.data?.reminderId, err?.message)
  })

  return worker
}
