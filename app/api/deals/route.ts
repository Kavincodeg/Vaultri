import { NextRequest, NextResponse } from 'next/server'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import {
  coreApiFetch,
  extractClientIp,
  ConflictError,
  UnprocessableEntityError,
  ValidationError,
  UnauthorizedError,
} from '@/lib/core-api/server'
import { mapDealStatus, mapPaymentStatus } from '@/lib/core-api/status-mapper'

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

  const rawTitle = typeof body.title === 'string' && body.title.trim().length > 0 ? body.title.trim() : (typeof body.description === 'string' ? body.description.trim() : '')
  const customerName = typeof body.customerName === 'string' ? body.customerName.trim() : ''
  const customerEmail = typeof body.customerEmail === 'string' ? body.customerEmail.trim() : null
  const customerPhone = typeof body.customerPhone === 'string' ? body.customerPhone.trim() : null
  const price = typeof body.price === 'number' && !isNaN(body.price) ? body.price : null
  const depositPercent = typeof body.depositPercent === 'number' && !isNaN(body.depositPercent) ? body.depositPercent : 30
  const dueDate = body.dueDate ? new Date(body.dueDate) : null
  const contractText = typeof body.contractText === 'string' ? body.contractText.trim() : null

  if (!rawTitle || rawTitle.length > 255 || !customerName || customerName.length > 255) {
    return NextResponse.json({ error: 'Title and customerName are required and must be <= 255 characters' }, { status: 400 })
  }
  if (customerEmail && customerEmail.length > 255) {
    return NextResponse.json({ error: 'customerEmail must be <= 255 characters' }, { status: 400 })
  }
  if (customerPhone && customerPhone.length > 32) {
    return NextResponse.json({ error: 'customerPhone must be <= 32 characters' }, { status: 400 })
  }
  if (price === null || price <= 0) {
    return NextResponse.json({ error: 'Price must be greater than zero' }, { status: 400 })
  }
  if (depositPercent < 1 || depositPercent > 100) {
    return NextResponse.json({ error: 'Deposit percent must be between 1 and 100' }, { status: 400 })
  }
  if (dueDate && isNaN(dueDate.getTime())) {
    return NextResponse.json({ error: 'Invalid due date' }, { status: 400 })
  }

  try {
    const deal = await coreApiFetch<any>({
      method: 'POST',
      path: '/api/v1/deals',
      token,
      clientIp: extractClientIp(req.headers),
      idempotencyKey,
      body: {
        title: rawTitle,
        customerName,
        customerEmail: customerEmail || undefined,
        customerPhone: customerPhone || undefined,
        price,
        depositPercent,
        dueDate: dueDate ? dueDate.toISOString() : undefined,
        contractText: contractText || undefined,
      },
    })

    return NextResponse.json({ dealId: deal.id, deal }, { status: 201 })
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
    if (err instanceof ValidationError) {
      return NextResponse.json({ error: 'Invalid deal data' }, { status: 400 })
    }
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to create deal' }, { status: 500 })
  }
}

export async function GET(req: NextRequest) {
  let token: string
  try {
    token = await getAccessTokenOrThrow(req)
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
  }

  try {
    const pageData = await coreApiFetch<{ items: any[]; totalElements: number }>({
      method: 'GET',
      path: '/api/v1/deals?page=0&size=100',
      token,
      clientIp: extractClientIp(req.headers),
    })

    const deals = (pageData.items || []).map((deal: any) => ({
      id: deal.id,
      sellerId: deal.sellerId,
      customerName: deal.customerName,
      customerPhone: deal.customerPhone ?? null,
      customerEmail: deal.customerEmail ?? null,
      description: deal.title,
      title: deal.title,
      price: deal.price,
      depositPercent: deal.depositPercent,
      depositAmount: deal.depositAmount,
      dueDate: deal.dueDate,
      status: mapDealStatus(deal.status),
      contractText: deal.contractText ?? '',
      createdAt: deal.createdAt,
      payments: [
        ...(deal.depositPayment
          ? [
              {
                id: deal.depositPayment.id,
                dealId: deal.id,
                razorpayLinkId: deal.depositPayment.razorpayPaymentLinkId,
                shortUrl: deal.depositPayment.paymentUrl,
                type: 'deposit',
                amount: deal.depositPayment.amount,
                status: mapPaymentStatus(deal.depositPayment.status),
              },
            ]
          : []),
        ...(deal.cancellationFeePayment
          ? [
              {
                id: deal.cancellationFeePayment.id,
                dealId: deal.id,
                razorpayLinkId: deal.cancellationFeePayment.razorpayPaymentLinkId,
                shortUrl: deal.cancellationFeePayment.paymentUrl,
                type: 'cancellation_fee',
                amount: deal.cancellationFeePayment.amount,
                status: mapPaymentStatus(deal.cancellationFeePayment.status),
              },
            ]
          : []),
      ],
      reminders: [],
      auditLogs: [],
    }))

    return NextResponse.json({ deals, totalElements: pageData.totalElements })
  } catch (err: unknown) {
    if (err instanceof UnauthorizedError) {
      return NextResponse.json({ code: 'session_refresh' }, { status: 401 })
    }
    return NextResponse.json({ error: 'Failed to fetch deals' }, { status: 500 })
  }
}
