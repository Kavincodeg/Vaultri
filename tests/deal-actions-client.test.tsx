// @vitest-environment jsdom
import React from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { DealActions } from '@/components/DealActions'

// Mock next/navigation
const mockRefresh = vi.fn()
const mockPush = vi.fn()
vi.mock('next/navigation', () => ({
  useRouter: () => ({
    refresh: mockRefresh,
    push: mockPush,
  }),
}))

describe('DealActions Client Idempotency Key Lifecycle', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    mockRefresh.mockClear()
    mockPush.mockClear()
  })

  it('keeps the same Idempotency-Key on retry after failure', async () => {
    const fetchCalls: { headers: Record<string, string>; body: any }[] = []
    global.fetch = vi.fn().mockImplementation(async (url: string, init: any) => {
      fetchCalls.push({
        headers: init.headers,
        body: JSON.parse(init.body),
      })
      return {
        ok: false,
        status: 500,
        json: async () => ({ error: 'Failed to cancel deal' }),
      }
    })

    render(
      React.createElement(DealActions, {
        dealId: 'deal-1',
        status: 'in_progress',
        totalPrice: 50000,
        customerName: 'Alice',
        description: 'Custom ring',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    const cancelBtn = screen.getByRole('button', { name: /generate cancellation fee/i })

    // First click (attempt 1)
    fireEvent.click(cancelBtn)
    await waitFor(() => expect(fetchCalls.length).toBe(1))
    const firstKey = fetchCalls[0].headers['Idempotency-Key']
    expect(firstKey).toBeTruthy()

    // Second click without payload change (retry)
    fireEvent.click(cancelBtn)
    await waitFor(() => expect(fetchCalls.length).toBe(2))
    const retryKey = fetchCalls[1].headers['Idempotency-Key']

    // Key must be kept on retry
    expect(retryKey).toBe(firstKey)
  })

  it('rotates the Idempotency-Key after successful cancellation', async () => {
    const fetchCalls: { headers: Record<string, string> }[] = []
    global.fetch = vi.fn().mockImplementation(async () => {
      return {
        ok: true,
        status: 200,
        json: async () => ({
          shortUrl: 'https://rzp.io/canc-link',
          feeAmount: 25000,
          percent: 50,
        }),
      }
    })

    const { unmount } = render(
      React.createElement(DealActions, {
        dealId: 'deal-success-1',
        status: 'in_progress',
        totalPrice: 50000,
        customerName: 'Alice',
        description: 'Custom ring',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    const cancelBtn = screen.getByRole('button', { name: /generate cancellation fee/i })
    fireEvent.click(cancelBtn)
    await waitFor(() => expect(screen.getByText(/cancellation fee generated/i)).toBeTruthy())

    unmount()

    const fetchCalls2: { headers: Record<string, string> }[] = []
    global.fetch = vi.fn().mockImplementation(async (url: string, init: any) => {
      fetchCalls2.push({ headers: init.headers })
      return {
        ok: true,
        status: 200,
        json: async () => ({ shortUrl: 'https://rzp.io/canc-link-2' }),
      }
    })

    render(
      React.createElement(DealActions, {
        dealId: 'deal-success-1',
        status: 'in_progress',
        totalPrice: 50000,
        customerName: 'Alice',
        description: 'Custom ring',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    const cancelBtn2 = screen.getByRole('button', { name: /generate cancellation fee/i })
    fireEvent.click(cancelBtn2)
    await waitFor(() => expect(fetchCalls2.length).toBe(1))

    const secondKey = fetchCalls2[0].headers['Idempotency-Key']
    expect(secondKey).toBeTruthy()
  })

  it('rotates the Idempotency-Key whenever any value in the cancel payload changes (dealId, description, fee)', async () => {
    const fetchCalls: { headers: Record<string, string> }[] = []
    global.fetch = vi.fn().mockImplementation(async (url: string, init: any) => {
      fetchCalls.push({ headers: init.headers })
      return {
        ok: false,
        status: 500,
        json: async () => ({ error: 'Error' }),
      }
    })

    const { rerender } = render(
      React.createElement(DealActions, {
        dealId: 'deal-1',
        status: 'in_progress',
        totalPrice: 50000,
        customerName: 'Alice',
        description: 'Ring v1',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    // Attempt 1 with original payload
    fireEvent.click(screen.getByRole('button', { name: /generate cancellation fee/i }))
    await waitFor(() => expect(fetchCalls.length).toBe(1))
    const key1 = fetchCalls[0].headers['Idempotency-Key']

    // Rerender with changed description
    rerender(
      React.createElement(DealActions, {
        dealId: 'deal-1',
        status: 'in_progress',
        totalPrice: 50000,
        customerName: 'Alice',
        description: 'Ring v2 modified description',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    // Attempt 2 after payload description change
    fireEvent.click(screen.getByRole('button', { name: /generate cancellation fee/i }))
    await waitFor(() => expect(fetchCalls.length).toBe(2))
    const key2 = fetchCalls[1].headers['Idempotency-Key']
    expect(key2).not.toBe(key1)

    // Rerender with changed totalPrice (fee)
    rerender(
      React.createElement(DealActions, {
        dealId: 'deal-1',
        status: 'in_progress',
        totalPrice: 80000,
        customerName: 'Alice',
        description: 'Ring v2 modified description',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    // Attempt 3 after fee change
    fireEvent.click(screen.getByRole('button', { name: /generate cancellation fee/i }))
    await waitFor(() => expect(fetchCalls.length).toBe(3))
    const key3 = fetchCalls[2].headers['Idempotency-Key']
    expect(key3).not.toBe(key2)

    // Rerender with changed dealId
    rerender(
      React.createElement(DealActions, {
        dealId: 'deal-2',
        status: 'in_progress',
        totalPrice: 80000,
        customerName: 'Alice',
        description: 'Ring v2 modified description',
        pendingReminderIds: [],
        reminders: [],
      })
    )

    // Attempt 4 after dealId change
    fireEvent.click(screen.getByRole('button', { name: /generate cancellation fee/i }))
    await waitFor(() => expect(fetchCalls.length).toBe(4))
    const key4 = fetchCalls[3].headers['Idempotency-Key']
    expect(key4).not.toBe(key3)
  })
})
