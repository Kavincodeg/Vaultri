/**
 * Sanitizes the 'next' URL parameter to ensure it is a same-origin path starting with a single "/".
 * Rejects protocol-relative URLs, absolute URLs, backslashes, encoded exploits, and redirect loops.
 */
export function sanitizeNextUrl(next: string | null | undefined): string {
  if (!next || typeof next !== 'string') return '/dashboard'
  let trimmed = next.trim()

  // Decode URI components to catch URL-encoded attacks (%2f%2f, %5c, %00, etc.)
  try {
    let prev = ''
    let depth = 0
    while (trimmed !== prev && depth < 3) {
      prev = trimmed
      trimmed = decodeURIComponent(trimmed).trim()
      depth++
    }
  } catch {
    return '/dashboard'
  }

  // Must start with exactly one '/' and not followed by another '/' or '\'
  if (!trimmed.startsWith('/') || trimmed.startsWith('//') || trimmed.startsWith('/\\') || trimmed.startsWith('/\/')) {
    return '/dashboard'
  }

  // Disallow backslashes anywhere in path (prevents browser path escaping)
  if (trimmed.includes('\\')) {
    return '/dashboard'
  }

  // Disallow any scheme or protocol (e.g. http:, https:, javascript:, data:)
  if (trimmed.includes('://') || /^[a-zA-Z][a-zA-Z0-9+.-]*:/.test(trimmed)) {
    return '/dashboard'
  }

  // Disallow control characters or newlines
  if (/[\u0000-\u001F\u007F-\u009F]/.test(trimmed)) {
    return '/dashboard'
  }

  // Prevent redirect loops back to auth endpoints
  if (trimmed.startsWith('/auth/refreshing') || trimmed.startsWith('/auth/signin')) {
    return '/dashboard'
  }

  return trimmed
}

/**
 * Handles the session refresh attempt for /auth/refreshing.
 * Treats "no session.user or session.error === true" as unusable:
 * makes exactly one attempt, calls signOut once, and redirects to /auth/signin without looping.
 */
export async function handleRefreshAttempt({
  update,
  target,
  signOut,
  routerReplace,
}: {
  update: () => Promise<any>
  target: string
  signOut: (options: { callbackUrl: string }) => Promise<any>
  routerReplace: (url: string) => void
}): Promise<{ status: 'authenticated' | 'unauthenticated'; target?: string }> {
  try {
    const updatedSession = await update()
    if (!updatedSession || !updatedSession.user || (updatedSession as any).error) {
      await signOut({ callbackUrl: '/auth/signin' })
      return { status: 'unauthenticated' }
    }
    routerReplace(target)
    return { status: 'authenticated', target }
  } catch {
    await signOut({ callbackUrl: '/auth/signin' })
    return { status: 'unauthenticated' }
  }
}
