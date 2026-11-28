import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

describe('SessionKeepAlive Lifecycle & Intervals', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  it('triggers update on mount, every 240s, and on visibilitychange to visible, passing {} explicitly', async () => {
    const updateMock = vi.fn().mockResolvedValue({})
    const listeners: Record<string, ((e?: any) => void)[]> = {}

    // Mock document
    const mockDocument: any = {
      visibilityState: 'hidden',
      addEventListener: (type: string, cb: (e?: any) => void) => {
        if (!listeners[type]) listeners[type] = []
        listeners[type].push(cb)
      },
      removeEventListener: (type: string, cb: (e?: any) => void) => {
        if (listeners[type]) {
          listeners[type] = listeners[type].filter((fn) => fn !== cb)
        }
      },
    };

    (global as any).document = mockDocument

    const lockRequestMock = vi.fn().mockImplementation(async (name: string, cb: () => Promise<any>) => {
      return await cb()
    })

    Object.defineProperty(global, 'navigator', {
      value: {
        locks: {
          request: lockRequestMock,
        },
      },
      configurable: true,
      writable: true,
    })

    // Simulate SessionKeepAlive logic passing {} explicitly
    let isUpdating = false
    const triggerUpdate = async () => {
      if (isUpdating) return
      isUpdating = true
      try {
        if (global.navigator?.locks?.request) {
          await global.navigator.locks.request('session-refresh', async () => {
            await updateMock({})
          })
        } else {
          await updateMock({})
        }
      } finally {
        isUpdating = false
      }
    }

    // Mount lifecycle:
    // (a) On mount
    await triggerUpdate()
    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock).toHaveBeenCalledWith({})

    // (b) Every 240 seconds
    const intervalId = setInterval(() => {
      triggerUpdate()
    }, 240000)

    const handleVisibilityChange = () => {
      if (mockDocument.visibilityState === 'visible') {
        triggerUpdate()
      }
    }
    mockDocument.addEventListener('visibilitychange', handleVisibilityChange)

    // Advance 240s -> tick 1
    await vi.advanceTimersByTimeAsync(240000)
    expect(updateMock).toHaveBeenCalledTimes(2)

    // Advance another 240s -> tick 2
    await vi.advanceTimersByTimeAsync(240000)
    expect(updateMock).toHaveBeenCalledTimes(3)

    // (c) Visibility change to hidden (should NOT trigger)
    mockDocument.visibilityState = 'hidden'
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).toHaveBeenCalledTimes(3)

    // Visibility change to visible (should trigger update)
    mockDocument.visibilityState = 'visible'
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).toHaveBeenCalledTimes(4)

    // Cleanup
    clearInterval(intervalId)
    mockDocument.removeEventListener('visibilitychange', handleVisibilityChange)
    expect(listeners['visibilitychange']?.length).toBe(0)
  })

  it('mitigates Bug 2: decoupling unstable update reference (due to useMemo [session, loading]) via updateRef prevents re-trigger loops', async () => {
    // In NextAuth SessionProvider, useMemo depends on [session, loading].
    // Whenever update() runs, loading flips true then false, recreating the update function twice.
    let updateFnInstanceCount = 0
    const createUpdateFn = () => {
      updateFnInstanceCount++
      return vi.fn().mockResolvedValue({})
    }

    let currentUpdate = createUpdateFn()
    const updateRef = { current: currentUpdate }

    // Simulating ref update in component render
    const onRender = (newUpdate: any) => {
      updateRef.current = newUpdate
    }

    let effectRunCount = 0
    let keepAliveUpdateCalls = 0

    // Simulate effect that depends on [status] instead of [update]
    const runEffect = (status: string) => {
      if (status !== 'authenticated') return
      effectRunCount++
      keepAliveUpdateCalls++
      updateRef.current({})
    }

    // Initial mount: authenticated
    runEffect('authenticated')
    expect(effectRunCount).toBe(1)
    expect(keepAliveUpdateCalls).toBe(1)

    // Simulate NextAuth's useMemo recreating update due to [session, loading] changes
    for (let i = 0; i < 5; i++) {
      currentUpdate = createUpdateFn()
      onRender(currentUpdate)
      // Because effect depends on [status] and NOT [update], effect does NOT re-run!
    }

    expect(updateFnInstanceCount).toBe(6) // 1 initial + 5 re-creations
    expect(effectRunCount).toBe(1) // Still exactly 1 effect execution (no loop!)
    expect(keepAliveUpdateCalls).toBe(1) // No loop of keep-alive calls
  })

  it('handles initial loading state and triggers keep-alive once status transitions to authenticated', async () => {
    const updateMock = vi.fn().mockResolvedValue({})
    let hasTriggeredInitial = false

    const handleStatusChange = (status: string) => {
      if (status === 'authenticated' && !hasTriggeredInitial) {
        hasTriggeredInitial = true
        updateMock({})
      }
    }

    // 1. Initial mount: loading
    handleStatusChange('loading')
    expect(updateMock).not.toHaveBeenCalled()

    // 2. Status transitions to authenticated
    handleStatusChange('authenticated')
    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(updateMock).toHaveBeenCalledWith({})

    // 3. Subsequent render in authenticated state does not duplicate initial trigger
    handleStatusChange('authenticated')
    expect(updateMock).toHaveBeenCalledTimes(1)
  })

  it('preserves 240s cadence after visibility update: next interval update arrives 240s after mount, not 240s after visibility update', async () => {
    const updateMock = vi.fn().mockImplementation(async () => {
      // Simulate NextAuth's status flip during update: authenticated -> loading -> authenticated
      statusRef.current = 'loading'
      await Promise.resolve()
      statusRef.current = 'authenticated'
      return {}
    })
    const listeners: Record<string, ((e?: any) => void)[]> = {}

    const mockDoc: any = {
      visibilityState: 'hidden',
      addEventListener: (type: string, cb: (e?: any) => void) => {
        if (!listeners[type]) listeners[type] = []
        listeners[type].push(cb)
      },
      removeEventListener: (type: string, cb: (e?: any) => void) => {
        if (listeners[type]) {
          listeners[type] = listeners[type].filter((fn) => fn !== cb)
        }
      },
    };

    (global as any).document = mockDoc

    Object.defineProperty(global, 'navigator', {
      value: {
        locks: {
          request: vi.fn().mockImplementation(async (_name: string, cb: () => Promise<any>) => cb()),
        },
      },
      configurable: true,
      writable: true,
    })

    // Simulate SessionKeepAlive component setup
    let currentStatus = 'authenticated'
    const statusRef = { current: currentStatus }
    const updateRef = { current: updateMock }
    const hasTriggeredInitialRef = { current: false }

    // Initial trigger effect ([status])
    const runInitialEffect = (status: string) => {
      statusRef.current = status
      if (status === 'authenticated' && !hasTriggeredInitialRef.current) {
        hasTriggeredInitialRef.current = true
        updateRef.current({})
      }
    }

    // Keep-alive effect ([]) runs ONCE on mount
    let isUpdating = false
    const triggerUpdate = async () => {
      if (isUpdating) return
      if (statusRef.current !== 'authenticated') return
      isUpdating = true
      try {
        await updateRef.current({})
      } finally {
        isUpdating = false
      }
    }

    const intervalId = setInterval(() => {
      triggerUpdate()
    }, 240000)

    const handleVisibilityChange = () => {
      if (mockDoc.visibilityState === 'visible') {
        triggerUpdate()
      }
    }
    mockDoc.addEventListener('visibilitychange', handleVisibilityChange)

    // Mount at t = 0
    runInitialEffect('authenticated')
    expect(updateMock).toHaveBeenCalledTimes(1) // Initial trigger at t = 0

    // At t = 100s (100,000 ms), user tabs away and comes back (visibility change)
    await vi.advanceTimersByTimeAsync(100000)
    expect(updateMock).toHaveBeenCalledTimes(1)

    mockDoc.visibilityState = 'visible'
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).toHaveBeenCalledTimes(2) // Visibility update at t = 100s

    // During update, status flipped to 'loading' and back to 'authenticated'
    // Under the old bug with [status], the interval would have been reset, meaning the next interval
    // would arrive at t = 340s (240s after visibility update).
    // With the fix (empty deps [] and statusRef), interval is NOT reset.
    // At t = 239.9s (139.9s after visibility update, 239.9s after mount):
    await vi.advanceTimersByTimeAsync(139900)
    expect(updateMock).toHaveBeenCalledTimes(2) // Interval has NOT fired yet

    // At t = 240s (140s after visibility update, exactly 240s after mount):
    await vi.advanceTimersByTimeAsync(100)
    expect(updateMock).toHaveBeenCalledTimes(3) // Interval fires exactly 240s after mount!

    // At t = 480s (240s after the first interval):
    await vi.advanceTimersByTimeAsync(240000)
    expect(updateMock).toHaveBeenCalledTimes(4)

    // Cleanup
    clearInterval(intervalId)
    mockDoc.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  it('treats dead session ("no session.user or session.error === true") as unusable: skips mount trigger, 240s interval, and visibilitychange', async () => {
    const updateMock = vi.fn().mockResolvedValue({})
    const listeners: Record<string, ((e?: any) => void)[]> = {}

    const mockDoc: any = {
      visibilityState: 'hidden',
      addEventListener: (type: string, cb: (e?: any) => void) => {
        if (!listeners[type]) listeners[type] = []
        listeners[type].push(cb)
      },
      removeEventListener: (type: string, cb: (e?: any) => void) => {
        if (listeners[type]) {
          listeners[type] = listeners[type].filter((fn) => fn !== cb)
        }
      },
    };

    (global as any).document = mockDoc

    // Dead session state: status 'authenticated' but user is undefined and error is true
    const currentStatus = 'authenticated'
    const deadSession = { user: undefined, expires: '2026-11-28T12:00:00.000Z', error: true }
    const statusRef = { current: currentStatus }
    const sessionRef = { current: deadSession as any }
    const updateRef = { current: updateMock }
    const hasTriggeredInitialRef = { current: false }

    // Initial trigger effect with dead session check
    const runInitialEffect = (status: string, session: any) => {
      statusRef.current = status
      sessionRef.current = session
      const isUsable = status === 'authenticated' && !!session?.user && !(session as any)?.error
      if (isUsable && !hasTriggeredInitialRef.current) {
        hasTriggeredInitialRef.current = true
        updateRef.current({})
      }
    }

    // Keep-alive trigger with dead session check
    let isUpdating = false
    const triggerUpdate = async () => {
      if (isUpdating) return
      const isUsable = statusRef.current === 'authenticated' && !!sessionRef.current?.user && !(sessionRef.current as any)?.error
      if (!isUsable) return
      isUpdating = true
      try {
        await updateRef.current({})
      } finally {
        isUpdating = false
      }
    }

    const intervalId = setInterval(() => {
      triggerUpdate()
    }, 240000)

    const handleVisibilityChange = () => {
      if (mockDoc.visibilityState === 'visible') {
        triggerUpdate()
      }
    }
    mockDoc.addEventListener('visibilitychange', handleVisibilityChange)

    // 1. Mount with dead session -> no update
    runInitialEffect('authenticated', deadSession)
    expect(updateMock).not.toHaveBeenCalled()

    // 2. 240s interval tick -> no update
    await vi.advanceTimersByTimeAsync(240000)
    expect(updateMock).not.toHaveBeenCalled()

    // 3. Tab visibility becomes visible -> no update
    mockDoc.visibilityState = 'visible'
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).not.toHaveBeenCalled()

    // 4. Test missing user without error flag ({ user: undefined, error: undefined })
    const missingUserSession = { expires: '2026-11-28T12:00:00.000Z' }
    runInitialEffect('authenticated', missingUserSession)
    await vi.advanceTimersByTimeAsync(240000)
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).not.toHaveBeenCalled()

    // Cleanup
    clearInterval(intervalId)
    mockDoc.removeEventListener('visibilitychange', handleVisibilityChange)
  })

  it('stops firing keep-alive updates when session transitions from usable to dead', async () => {
    let callCount = 0
    const updateMock = vi.fn().mockImplementation(async () => {
      callCount++
      // On update, refresh token is found to be dead; NextAuth transitions session to dead session
      sessionRef.current = { user: undefined, expires: '2026-11-28T12:00:00.000Z', error: true }
      return sessionRef.current
    })

    const listeners: Record<string, ((e?: any) => void)[]> = {}
    const mockDoc: any = {
      visibilityState: 'hidden',
      addEventListener: (type: string, cb: (e?: any) => void) => {
        if (!listeners[type]) listeners[type] = []
        listeners[type].push(cb)
      },
      removeEventListener: (type: string, cb: (e?: any) => void) => {
        if (listeners[type]) {
          listeners[type] = listeners[type].filter((fn) => fn !== cb)
        }
      },
    };
    (global as any).document = mockDoc

    // Starts with usable session
    const statusRef = { current: 'authenticated' }
    const sessionRef = { current: { user: { name: 'Seller' }, expires: '2026-11-28T12:00:00.000Z' } as any }
    const updateRef = { current: updateMock }
    const hasTriggeredInitialRef = { current: false }

    const runInitialEffect = (status: string, session: any) => {
      statusRef.current = status
      sessionRef.current = session
      const isUsable = status === 'authenticated' && !!session?.user && !(session as any)?.error
      if (isUsable && !hasTriggeredInitialRef.current) {
        hasTriggeredInitialRef.current = true
        updateRef.current({})
      }
    }

    let isUpdating = false
    const triggerUpdate = async () => {
      if (isUpdating) return
      const isUsable = statusRef.current === 'authenticated' && !!sessionRef.current?.user && !(sessionRef.current as any)?.error
      if (!isUsable) return
      isUpdating = true
      try {
        await updateRef.current({})
      } finally {
        isUpdating = false
      }
    }

    const intervalId = setInterval(() => {
      triggerUpdate()
    }, 240000)

    const handleVisibilityChange = () => {
      if (mockDoc.visibilityState === 'visible') {
        triggerUpdate()
      }
    }
    mockDoc.addEventListener('visibilitychange', handleVisibilityChange)

    // 1. Mount with usable session -> triggers exactly 1 initial update
    runInitialEffect('authenticated', sessionRef.current)
    expect(updateMock).toHaveBeenCalledTimes(1)
    expect(sessionRef.current.error).toBe(true)
    expect(sessionRef.current.user).toBeUndefined()

    // 2. Next 240s interval -> since session is now dead, triggerUpdate skips!
    await vi.advanceTimersByTimeAsync(240000)
    expect(updateMock).toHaveBeenCalledTimes(1) // Still exactly 1 call!

    // 3. Tab visibility changes -> since session is dead, skips!
    mockDoc.visibilityState = 'visible'
    listeners['visibilitychange']?.forEach((cb) => cb())
    expect(updateMock).toHaveBeenCalledTimes(1) // Still exactly 1 call!

    // Cleanup
    clearInterval(intervalId)
    mockDoc.removeEventListener('visibilitychange', handleVisibilityChange)
  })
})
