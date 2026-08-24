/**
 * BullMQ job queue — reminder delivery with retry logic.
 *
 * For the demo / free-tier deployment, ENQUEUE_REMINDERS is not set,
 * so enqueueReminder() is a no-op. Reminders are triggered manually
 * from the deal detail page (DealActions → "Send Reminder Now").
 *
 * To enable automatic background delivery in production:
 *   1. Set ENQUEUE_REMINDERS=true in your environment
 *   2. Deploy worker.ts as a background process (Render, Railway, Fly.io)
 *
 * bullmq and ioredis are imported lazily so webpack does not bundle them.
 * next.config.js lists them in serverComponentsExternalPackages for safety.
 */

const QUEUE_NAME = 'reminders'
const MAX_RETRIES = 3

/**
 * Enqueue a reminder job.
 * No-op unless ENQUEUE_REMINDERS=true and REDIS_URL are both set.
 *
 * @param reminderId  DB Reminder.id
 * @param delayMs     Milliseconds until the job should fire (0 = immediate)
 */
export async function enqueueReminder(reminderId: string, delayMs: number): Promise<void> {
  // Demo / free-tier mode — skip automatic queueing entirely.
  // Reminders are sent manually via the dashboard button.
  if (process.env.ENQUEUE_REMINDERS !== 'true') return

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
 * Only used when running worker.ts as a standalone background process.
 * Not needed for demo / free-tier deployments.
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
