import { NextRequest, NextResponse } from 'next/server'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import {
  coreApiFetch,
  extractClientIp,
  NotFoundError,
  ConflictError,
  UnauthorizedError,
} from '@/lib/core-api/server'

export async function POST(
  req: NextRequest,
  { params }: { params: { id: string } }
) {
  let token: string
  try {
    token = await getAccessTokenOrThrow(req)
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  const dealId = params?.id
  if (!dealId) {
    return NextResponse.json({ error: 'Deal ID is required' }, { status: 400 })
  }

  try {
    await coreApiFetch({
      method: 'POST',
      path: `/api/v1/deals/${encodeURIComponent(dealId)}/complete`,
      token,
      clientIp: extractClientIp(req.headers),
    })

    return NextResponse.json({ success: true })
  } catch (err: unknown) {
    if (err instanceof NotFoundError) {
      return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
    }
    if (err instanceof ConflictError) {
      return NextResponse.json({ error: 'Illegal deal transition' }, { status: 409 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to complete deal' }, { status: 500 })
  }
}
