import { NextRequest, NextResponse } from 'next/server'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import {
  coreApiFetch,
  extractClientIp,
  NotFoundError,
  UnauthorizedError,
} from '@/lib/core-api/server'
import { mapDealStatus, mapPaymentStatus } from '@/lib/core-api/status-mapper'

export async function GET(
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
    const deal = await coreApiFetch<any>({
      method: 'GET',
      path: `/api/v1/deals/${encodeURIComponent(dealId)}`,
      token,
      clientIp: extractClientIp(req.headers),
    })

    return NextResponse.json({
      dealStatus: mapDealStatus(deal.status),
      depositStatus: deal.depositPayment ? mapPaymentStatus(deal.depositPayment.status) : null,
    })
  } catch (err: unknown) {
    if (err instanceof NotFoundError) {
      return NextResponse.json({ error: 'Deal not found' }, { status: 404 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to fetch deal status' }, { status: 500 })
  }
}
