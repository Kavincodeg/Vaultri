// @vitest-environment jsdom
import React from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, act } from '@testing-library/react'
import { SessionProvider } from 'next-auth/react'
import { SessionKeepAlive } from '@/components/SessionKeepAlive'

describe('SessionKeepAlive Real Provider Tests', () => {
  let fetchCalls: { url: string; method: string; body?: any }[] = []

  beforeEach(() => {
    vi.useFakeTimers()
    fetchCalls = []

    // Navigator locks shim
    Object.defineProperty(window.navigator, 'locks', {
      value: {
        request: vi.fn().mockImplementation(async (_name: string, cb: () => Promise<any>) => cb()),
      },
      configurable: true,
      writable: true,
    })

    // Mock global fetch
    const mockFetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : (input as any).url
      const method = (init?.method || 'GET').toUpperCase()
      const body = init?.body ? (typeof init.body === 'string' ? JSON.parse(init.body) : init.body) : undefined

      fetchCalls.push({ url, method, body })

      if (url.includes('/api/auth/csrf')) {
        return {
          ok: true,
          status: 200,
          headers: new Headers({ 'Content-Type': 'application/json' }),
          json: async () => ({ csrfToken: 'fake-csrf-token' }),
          text: async () => JSON.stringify({ csrfToken: 'fake-csrf-token' }),
        }
      }

      if (url.includes('/api/auth/session')) {
        if (method === 'POST') {
          return {
            ok: true,
            status: 200,
            headers: new Headers({ 'Content-Type': 'application/json' }),
            json: async () => ({
              user: { name: 'Seller User', email: 'seller@example.com' },
              expires: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
            }),
            text: async () => JSON.stringify({
              user: { name: 'Seller User', email: 'seller@example.com' },
              expires: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
            }),
          }
        }
        return {
          ok: true,
          status: 200,
          headers: new Headers({ 'Content-Type': 'application/json' }),
          json: async () => ({}),
          text: async () => '{}',
        }
      }

      return {
        ok: true,
        status: 200,
        headers: new Headers({ 'Content-Type': 'application/json' }),
        json: async () => ({}),
        text: async () => '{}',
      }
    })

    global.fetch = mockFetch
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  const initialSession = {
    user: { name: 'Seller User', email: 'seller@example.com' },
    expires: new Date(Date.now() + 24 * 3600 * 1000).toISOString(),
  }

  // Helper to flush microtasks and settle pending React state and fetch promises
  async function settleResponses() {
    await act(async () => {
      for (let i = 0; i < 20; i++) {
        await Promise.resolve()
      }
    })
  }

  function getSessionPostCalls() {
    return fetchCalls.filter((c) => c.method === 'POST' && c.url.includes('/api/auth/session'))
  }

  function getSessionGetCalls() {
    return fetchCalls.filter((c) => c.method === 'GET' && c.url.includes('/api/auth/session'))
  }

  it('verifies mount POST, 60s quiet window, 240s interval POST, and unmount silence', async () => {
    let unmount: () => void
    await act(async () => {
      const res = render(
        React.createElement(SessionProvider, {
          session: initialSession,
          refetchInterval: 0,
          refetchOnWindowFocus: false,
          children: React.createElement(SessionKeepAlive, null),
        })
      )
      unmount = res.unmount
    })

    // Settle responses from mount trigger
    await settleResponses()

    // Assert: on mount exactly one POST to /api/auth/session with csrfToken in the body
    const postCallsAfterMount = getSessionPostCalls()
    expect(postCallsAfterMount.length).toBe(1)
    expect(postCallsAfterMount[0].body).toHaveProperty('csrfToken', 'fake-csrf-token')

    // Assert: no GET to /api/auth/session at any point
    expect(getSessionGetCalls().length).toBe(0)

    // Assert: after responses settle, 60 s later no further requests
    const callsBefore60s = fetchCalls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(60000)
    })
    await settleResponses()
    expect(fetchCalls.length).toBe(callsBefore60s)
    expect(getSessionPostCalls().length).toBe(1)

    // Assert: at 240 s after mount exactly one more POST (180 s after the 60 s check)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(180000)
    })
    await settleResponses()
    expect(getSessionPostCalls().length).toBe(2)

    // Assert: after unmount, advancing 10 minutes produces no requests
    unmount!()
    const callsAtUnmount = fetchCalls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600000)
    })
    await settleResponses()
    expect(fetchCalls.length).toBe(callsAtUnmount)

    // Verify again: no GET to /api/auth/session at any point
    expect(getSessionGetCalls().length).toBe(0)
  })

  it('verifies visibility change adds exactly one POST and next interval POST still lands 240s after mount', async () => {
    let unmount: () => void

    // Set initial visibility to hidden so transitioning to visible triggers listener
    Object.defineProperty(document, 'visibilityState', {
      value: 'hidden',
      writable: true,
      configurable: true,
    })

    await act(async () => {
      const res = render(
        React.createElement(SessionProvider, {
          session: initialSession,
          refetchInterval: 0,
          refetchOnWindowFocus: false,
          children: React.createElement(SessionKeepAlive, null),
        })
      )
      unmount = res.unmount
    })

    // Settle responses from initial mount trigger
    await settleResponses()
    expect(getSessionPostCalls().length).toBe(1)
    expect(getSessionGetCalls().length).toBe(0)

    // At t = 100s: visibility change to visible
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100000)
    })
    await settleResponses()
    expect(getSessionPostCalls().length).toBe(1)

    // Trigger visibility change to visible
    await act(async () => {
      Object.defineProperty(document, 'visibilityState', {
        value: 'visible',
        writable: true,
        configurable: true,
      })
      document.dispatchEvent(new Event('visibilitychange'))
    })
    await settleResponses()

    // Assert: a visibility change to visible adds exactly one POST
    expect(getSessionPostCalls().length).toBe(2)

    // Advance to t = 239.9s (139.9s after visibility update, 239.9s after mount)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(139900)
    })
    await settleResponses()
    // Interval POST has NOT fired yet
    expect(getSessionPostCalls().length).toBe(2)

    // Advance 100ms to exactly t = 240s after mount
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100)
    })
    await settleResponses()

    // Assert: the next interval POST still lands 240 s after mount
    expect(getSessionPostCalls().length).toBe(3)

    // Assert: no GET to /api/auth/session at any point
    expect(getSessionGetCalls().length).toBe(0)

    // After unmount, advancing 10 minutes produces no requests
    unmount!()
    const callsAfterUnmount = fetchCalls.length
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600000)
    })
    await settleResponses()
    expect(fetchCalls.length).toBe(callsAfterUnmount)
  })
})
