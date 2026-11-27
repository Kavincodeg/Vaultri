// Ensures module is only imported in server environments
if (typeof window !== 'undefined') {
  throw new Error('This module can only be loaded on the server')
}

import net from 'net'

export class UnauthorizedError extends Error {
  constructor(message = 'Unauthorized') {
    super(message)
    this.name = 'UnauthorizedError'
  }
}

export class RateLimitedError extends Error {
  readonly retryAfter: number
  constructor(retryAfter = 60, message = 'Too many requests') {
    super(message)
    this.name = 'RateLimitedError'
    this.retryAfter = retryAfter
  }
}

export class ValidationError extends Error {
  constructor(message = 'Validation failed') {
    super(message)
    this.name = 'ValidationError'
  }
}

export class ConflictError extends Error {
  constructor(message = 'Conflict') {
    super(message)
    this.name = 'ConflictError'
  }
}

export class UnavailableError extends Error {
  constructor(message = 'Service unavailable') {
    super(message)
    this.name = 'UnavailableError'
  }
}

export interface ClientIpContext {
  headers?: Headers | Record<string, string | string[] | undefined>
}

/**
 * Validates and extracts a single client IP address from platform headers.
 * Never passes through user-supplied chains or multi-entry headers.
 */
export function extractClientIp(headers?: Headers | Record<string, string | string[] | undefined>): string | null {
  if (!headers) return null

  const getHeader = (name: string): string | null => {
    if (typeof (headers as Headers).get === 'function') {
      return (headers as Headers).get(name)
    }
    const val = (headers as Record<string, string | string[] | undefined>)[name.toLowerCase()] ||
                (headers as Record<string, string | string[] | undefined>)[name]
    if (Array.isArray(val)) return val[0] || null
    return typeof val === 'string' ? val : null
  }

  // Look for standard platform headers that provide a single validated IP
  const candidate = getHeader('cf-connecting-ip') ||
                    getHeader('x-real-ip') ||
                    getHeader('fastly-client-ip') ||
                    getHeader('true-client-ip') ||
                    getHeader('x-client-ip')

  if (candidate) {
    const trimmed = candidate.trim()
    if (net.isIP(trimmed) !== 0) {
      return trimmed
    }
    return null
  }

  // If fallback to x-forwarded-for, ensure it's strictly a single IP literal, not a comma-separated list
  const xff = getHeader('x-forwarded-for')
  if (xff) {
    const trimmed = xff.trim()
    if (!trimmed.includes(',') && net.isIP(trimmed) !== 0) {
      return trimmed
    }
  }

  return null
}

export interface CoreApiRequestOptions {
  method?: string
  path: string
  body?: unknown
  token?: string
  clientIp?: string | null
}

export async function coreApiFetch<T>(options: CoreApiRequestOptions): Promise<T> {
  const baseUrl = process.env.CORE_API_URL
  if (!baseUrl) {
    throw new UnavailableError('Backend service configuration missing')
  }

  const internalSecret = process.env.CORE_API_INTERNAL_SECRET

  const headers: Record<string, string> = {
    'Accept': 'application/json',
  }

  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  if (options.token) {
    headers['Authorization'] = `Bearer ${options.token}`
  }

  // Client IP Forwarding:
  // If clientIp is valid IPv4/IPv6 AND internalSecret is present, send both X-Forwarded-For and X-Internal-Secret.
  // If no valid address, send neither header.
  if (options.clientIp && net.isIP(options.clientIp) !== 0 && internalSecret) {
    headers['X-Forwarded-For'] = options.clientIp
    headers['X-Internal-Secret'] = internalSecret
  }

  const controller = new AbortController()
  const timeoutId = setTimeout(() => controller.abort(), 8000)

  try {
    const response = await fetch(`${baseUrl.replace(/\/+$/, '')}${options.path}`, {
      method: options.method || 'GET',
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      signal: controller.signal,
    })

    if (!response.ok) {
      if (response.status === 401) {
        throw new UnauthorizedError()
      }
      if (response.status === 429) {
        const retryAfterHeader = response.headers.get('retry-after')
        let retryAfter = 60
        if (retryAfterHeader) {
          const parsed = parseInt(retryAfterHeader, 10)
          if (!isNaN(parsed) && parsed > 0) {
            retryAfter = parsed
          }
        }
        throw new RateLimitedError(retryAfter)
      }
      if (response.status === 400 || response.status === 422) {
        throw new ValidationError()
      }
      if (response.status === 409) {
        throw new ConflictError()
      }
      throw new UnavailableError('Service returned an error status')
    }

    if (response.status === 204) {
      return undefined as T
    }

    return (await response.json()) as T
  } catch (error: unknown) {
    if (error instanceof UnauthorizedError ||
        error instanceof RateLimitedError ||
        error instanceof ValidationError ||
        error instanceof ConflictError ||
        error instanceof UnavailableError) {
      throw error
    }
    // Network errors, timeouts, aborts
    throw new UnavailableError('Failed to communicate with service')
  } finally {
    clearTimeout(timeoutId)
  }
}
