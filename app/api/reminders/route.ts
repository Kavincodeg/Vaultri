import { NextRequest, NextResponse } from 'next/server'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import {
  coreApiFetch,
  extractClientIp,
  ConflictError,
  NotFoundError,
  UnauthorizedError,
} from '@/lib/core-api/server'

export async function POST(req: NextRequest) {
  let token: string
  try {
    token = await getAccessTokenOrThrow(req)
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  let body: any
  try {
    body = await req.json()
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 })
  }

  const dealId = body?.dealId
  if (!dealId || typeof dealId !== 'string') {
    return NextResponse.json({ error: 'Missing dealId' }, { status: 400 })
  }

  try {
    await coreApiFetch({
      method: 'POST',
      path: `/api/v1/deals/${encodeURIComponent(dealId)}/reminders`,
      token,
      clientIp: extractClientIp(req.headers),
    })

    return NextResponse.json({ message: 'Reminder queued', status: 'queued' }, { status: 202 })
  } catch (err: unknown) {
    if (err instanceof ConflictError) {
      return NextResponse.json(
        { error: 'A manual reminder is already pending for this deal' },
        { status: 409 }
      )
    }
    if (err instanceof NotFoundError) {
      return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to queue reminder' }, { status: 500 })
  }
}
