import { NextRequest, NextResponse } from 'next/server'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import {
  coreApiFetch,
  extractClientIp,
  ConflictError,
  UnprocessableEntityError,
  NotFoundError,
  ValidationError,
  UnauthorizedError,
} from '@/lib/core-api/server'

// mirrors core-api vaultri.cancellation.fee-percent
const CANCELLATION_FEE_PERCENT = 50

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

  const idempotencyKey = req.headers.get('idempotency-key')?.trim()
  if (!idempotencyKey || idempotencyKey.length > 255) {
    return NextResponse.json(
      { error: 'Idempotency-Key header is required and must be between 1 and 255 characters' },
      { status: 400 }
    )
  }

  let body: any
  try {
    body = await req.json()
  } catch {
    return NextResponse.json({ error: 'Invalid JSON' }, { status: 400 })
  }

  const { dealId, reason } = body || {}
  if (!dealId || typeof dealId !== 'string') {
    return NextResponse.json({ error: 'dealId is required' }, { status: 400 })
  }

  const clientIp = extractClientIp(req.headers)

  // Cancellation fee must not come from the client.
  // Ignore client totalPrice and requestedFeePaise.
  // Fetch authoritative deal from core-api first:
  let deal: any
  try {
    deal = await coreApiFetch<any>({
      method: 'GET',
      path: `/api/v1/deals/${encodeURIComponent(dealId)}`,
      token,
      clientIp,
    })
  } catch (err: unknown) {
    if (err instanceof NotFoundError) {
      return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to cancel deal' }, { status: 500 })
  }

  if (!deal || typeof deal.price !== 'number') {
    return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
  }

  // Compute requestedFeePaise from the deal's authoritative price
  const requestedFeePaise = Math.round((deal.price * CANCELLATION_FEE_PERCENT) / 100)

  try {
    const cancelledDeal = await coreApiFetch<any>({
      method: 'POST',
      path: `/api/v1/deals/${encodeURIComponent(dealId)}/cancel`,
      token,
      clientIp,
      idempotencyKey,
      body: {
        requestedFeePaise,
        reason: typeof reason === 'string' ? reason.slice(0, 500) : 'Customer requested cancellation',
      },
    })

    const cancPayment = cancelledDeal?.cancellationFeePayment
    return NextResponse.json({
      shortUrl: cancPayment?.paymentUrl || '',
      feeAmount: cancPayment?.amount ?? requestedFeePaise,
      percent: CANCELLATION_FEE_PERCENT,
      draftMessage: 'Cancellation fee link generated per agreement terms.',
    })
  } catch (err: unknown) {
    if (err instanceof ConflictError) {
      const responseHeaders: Record<string, string> = {}
      if (err.retryAfter !== undefined) {
        responseHeaders['Retry-After'] = String(err.retryAfter)
      }
      return NextResponse.json(
        { error: 'A request with this Idempotency-Key is currently in progress. Please retry shortly.' },
        { status: 409, headers: responseHeaders }
      )
    }
    if (err instanceof UnprocessableEntityError) {
      return NextResponse.json(
        { error: 'Idempotency key was previously used with a different request payload' },
        { status: 422 }
      )
    }
    if (err instanceof NotFoundError) {
      return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
    }
    if (err instanceof ValidationError) {
      return NextResponse.json({ error: 'Invalid cancellation request' }, { status: 400 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to cancel deal' }, { status: 500 })
  }
}
