import { describe, it, expect, vi, beforeEach } from 'vitest'
import { NextRequest } from 'next/server'
import * as sessionModule from '@/lib/core-api/session'
import * as serverModule from '@/lib/core-api/server'
import { POST as createDealHandler, GET as listDealsHandler } from '@/app/api/deals/route'
import { POST as completeDealHandler } from '@/app/api/deals/[id]/complete/route'
import { GET as getDealStatusHandler } from '@/app/api/deals/[id]/status/route'
import { POST as cancelDealHandler } from '@/app/api/cancellation/route'
import { POST as createReminderHandler } from '@/app/api/reminders/route'

describe('Route Handlers Migration to Core-API', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  describe('1. POST /api/deals', () => {
    it('returns 401 {"code":"session_refresh"} when getAccessTokenOrThrow throws NeedsRefreshError', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new sessionModule.NeedsRefreshError())

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          title: 'Custom item',
          customerName: 'Priya',
          price: 50000,
          dueDate: new Date().toISOString(),
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data).toEqual({ code: 'session_refresh' })
    })

    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          title: 'Custom item',
          customerName: 'Priya',
          price: 50000,
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('returns 400 when JSON body is malformed', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: '{ malformed json',
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toBe('Invalid JSON')
    })

    it('returns 400 when Idempotency-Key header is missing or invalid', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          title: 'Custom item',
          customerName: 'Priya',
          price: 50000,
          dueDate: new Date().toISOString(),
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toContain('Idempotency-Key')
    })

    it('returns 400 on input validation failure (missing title, invalid price)', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          title: '',
          customerName: 'Priya',
          price: -50,
          dueDate: 'invalid-date',
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(400)
    })

    it('successfully calls core-api POST /api/v1/deals with client IP and Idempotency-Key', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      const coreApiSpy = vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue({
        id: '22222222-2222-4222-8222-222222222222',
        title: 'Custom Necklace',
        price: 50000,
        status: 'DEPOSIT_PENDING',
      })

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
          'cf-connecting-ip': '1.2.3.4',
        },
        body: JSON.stringify({
          description: 'Custom Necklace',
          customerName: 'Priya Sharma',
          price: 50000,
          depositPercent: 30,
          dueDate: '2026-12-01T00:00:00Z',
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(201)
      const data = await res.json()
      expect(data.dealId).toBe('22222222-2222-4222-8222-222222222222')
      expect(coreApiSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'POST',
          path: '/api/v1/deals',
          idempotencyKey: '11111111-1111-4111-8111-111111111111',
          clientIp: '1.2.3.4',
          token: 'valid-access-token',
        })
      )
    })

    it('maps 409 in-progress with Retry-After passed through', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(
        new serverModule.ConflictError('Request in progress', 2)
      )

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          description: 'Custom Necklace',
          customerName: 'Priya Sharma',
          price: 50000,
          dueDate: '2026-12-01T00:00:00Z',
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(409)
      expect(res.headers.get('Retry-After')).toBe('2')
      const data = await res.json()
      expect(data.error).toBe('A request with this Idempotency-Key is currently in progress. Please retry shortly.')
    })

    it('maps 422 idempotency payload conflict to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(
        new serverModule.UnprocessableEntityError('Payload conflict')
      )

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          description: 'Custom Necklace',
          customerName: 'Priya Sharma',
          price: 50000,
          dueDate: '2026-12-01T00:00:00Z',
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(422)
      const data = await res.json()
      expect(data.error).toBe('Idempotency key was previously used with a different request payload')
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Internal server error'))

      const req = new NextRequest('http://localhost:3000/api/deals', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': '11111111-1111-4111-8111-111111111111',
        },
        body: JSON.stringify({
          title: 'Custom item',
          customerName: 'Priya',
          price: 50000,
        }),
      })

      const res = await createDealHandler(req)
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to create deal')
    })
  })

  describe('2. GET /api/deals', () => {
    it('returns 401 {"code":"session_refresh"} on NeedsRefreshError', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new sessionModule.NeedsRefreshError())

      const res = await listDealsHandler(new NextRequest('http://localhost:3000/api/deals'))
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data).toEqual({ code: 'session_refresh' })
    })

    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const res = await listDealsHandler(new NextRequest('http://localhost:3000/api/deals'))
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('returns deals list fetched from core-api with mapped statuses', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue({
        items: [
          {
            id: 'deal-1',
            title: 'Necklace',
            price: 50000,
            status: 'DEPOSIT_PAID',
            depositPayment: { id: 'p1', status: 'PAID', amount: 15000 },
          },
        ],
        totalElements: 1,
      })

      const res = await listDealsHandler(new NextRequest('http://localhost:3000/api/deals'))
      expect(res.status).toBe(200)
      const data = await res.json()
      expect(data.deals).toBeDefined()
      expect(data.deals[0].status).toBe('deposit_paid')
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Server error'))

      const res = await listDealsHandler(new NextRequest('http://localhost:3000/api/deals'))
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to fetch deals')
    })
  })

  describe('3. POST /api/deals/[id]/complete', () => {
    it('returns 401 {"code":"session_refresh"} on NeedsRefreshError', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new sessionModule.NeedsRefreshError())

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/complete', { method: 'POST' })
      const res = await completeDealHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data).toEqual({ code: 'session_refresh' })
    })

    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/complete', { method: 'POST' })
      const res = await completeDealHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('returns 404 when core-api returns 404 not found', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new serverModule.NotFoundError('Deal not found'))

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/complete', { method: 'POST' })
      const res = await completeDealHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(404)
      const data = await res.json()
      expect(data.error).toBe('Deal not found')
    })

    it('returns 200 { success: true } on successful completion', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue({ id: 'deal-1', status: 'COMPLETED' })

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/complete', { method: 'POST' })
      const res = await completeDealHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(200)
      const data = await res.json()
      expect(data).toEqual({ success: true })
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Server crash'))

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/complete', { method: 'POST' })
      const res = await completeDealHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to complete deal')
    })
  })

  describe('4. GET /api/deals/[id]/status', () => {
    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/status')
      const res = await getDealStatusHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('returns dealStatus and depositStatus mapped from core-api', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue({
        id: 'deal-1',
        status: 'DEPOSIT_PENDING',
        depositPayment: {
          status: 'PENDING',
        },
      })

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/status')
      const res = await getDealStatusHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(200)
      const data = await res.json()
      expect(data).toEqual({
        dealStatus: 'deposit_pending',
        depositStatus: 'created',
      })
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Database error'))

      const req = new NextRequest('http://localhost:3000/api/deals/deal-1/status')
      const res = await getDealStatusHandler(req, { params: { id: 'deal-1' } })
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to fetch deal status')
    })
  })

  describe('5. POST /api/cancellation', () => {
    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Idempotency-Key': 'key-1' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('returns 400 if Idempotency-Key header is missing', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(400)
      const data = await res.json()
      expect(data.error).toContain('Idempotency-Key')
    })

    it('ignores client totalPrice and requestedFeePaise, fetches deal via GET /api/v1/deals/{dealId}, and sets fee to 50% of deal price', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      
      const coreApiSpy = vi.spyOn(serverModule, 'coreApiFetch').mockImplementation(async (opts: any) => {
        if (opts.method === 'GET' && opts.path === '/api/v1/deals/deal-1') {
          return {
            id: 'deal-1',
            price: 60000, // Authoritative price in paise (600 rupees)
            status: 'IN_PROGRESS',
          }
        }
        if (opts.method === 'POST' && opts.path === '/api/v1/deals/deal-1/cancel') {
          return {
            id: 'deal-1',
            status: 'CANCELLED',
            cancellationFeePayment: {
              id: 'pay-canc-1',
              status: 'PENDING',
              amount: opts.body.requestedFeePaise,
              paymentUrl: 'https://rzp.io/i/test-canc',
            },
          }
        }
        throw new Error(`Unexpected call: ${opts.path}`)
      })

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1234',
        },
        body: JSON.stringify({
          dealId: 'deal-1',
          totalPrice: 100, // Tampered client price should be IGNORED
          requestedFeePaise: 50, // Tampered client fee should be IGNORED
          reason: 'Customer cancelled',
        }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(200)
      const data = await res.json()
      expect(data.feeAmount).toBe(30000) // Exactly 50% of 60000
      expect(data.shortUrl).toBe('https://rzp.io/i/test-canc')

      // Check that POST /api/v1/deals/deal-1/cancel received exactly 30000
      expect(coreApiSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          method: 'POST',
          path: '/api/v1/deals/deal-1/cancel',
          body: expect.objectContaining({
            requestedFeePaise: 30000,
          }),
        })
      )
    })

    it('returns 404 fixed text when core-api GET returns NotFoundError', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new serverModule.NotFoundError('Deal not found'))

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1234',
        },
        body: JSON.stringify({
          dealId: 'deal-not-found',
        }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(404)
      const data = await res.json()
      expect(data.error).toBe('Deal not found')
    })

    it('maps cancellation 409 in progress with Retry-After passed through', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockImplementation(async (opts: any) => {
        if (opts.method === 'GET') {
          return { id: 'deal-1', price: 50000 }
        }
        throw new serverModule.ConflictError('Request in progress', 3)
      })

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1234',
        },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(409)
      expect(res.headers.get('Retry-After')).toBe('3')
      const data = await res.json()
      expect(data.error).toBe('A request with this Idempotency-Key is currently in progress. Please retry shortly.')
    })

    it('maps cancellation 422 idempotency payload conflict to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockImplementation(async (opts: any) => {
        if (opts.method === 'GET') {
          return { id: 'deal-1', price: 50000 }
        }
        throw new serverModule.UnprocessableEntityError('Payload conflict')
      })

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1234',
        },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(422)
      const data = await res.json()
      expect(data.error).toBe('Idempotency key was previously used with a different request payload')
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Gateway error'))

      const req = new NextRequest('http://localhost:3000/api/cancellation', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': 'key-1234',
        },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await cancelDealHandler(req)
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to cancel deal')
    })
  })

  describe('6. POST /api/reminders', () => {
    it('returns 401 when no session exists', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new Error('No token'))

      const req = new NextRequest('http://localhost:3000/api/reminders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await createReminderHandler(req)
      expect(res.status).toBe(401)
      const data = await res.json()
      expect(data.error).toBe('Unauthorized')
    })

    it('maps 202 to "reminder queued" response', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue(undefined)

      const req = new NextRequest('http://localhost:3000/api/reminders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await createReminderHandler(req)
      expect(res.status).toBe(202)
      const data = await res.json()
      expect(data.message).toBe('Reminder queued')
      expect(data.status).toBe('queued')
    })

    it('maps 409 to fixed "already pending" text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(
        new serverModule.ConflictError('A manual reminder is already pending for this deal')
      )

      const req = new NextRequest('http://localhost:3000/api/reminders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await createReminderHandler(req)
      expect(res.status).toBe(409)
      const data = await res.json()
      expect(data.error).toBe('A manual reminder is already pending for this deal')
    })

    it('maps 5xx server error to fixed text', async () => {
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      vi.spyOn(serverModule, 'coreApiFetch').mockRejectedValue(new Error('Service failure'))

      const req = new NextRequest('http://localhost:3000/api/reminders', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ dealId: 'deal-1' }),
      })

      const res = await createReminderHandler(req)
      expect(res.status).toBe(500)
      const data = await res.json()
      expect(data.error).toBe('Failed to queue reminder')
    })
  })
})
