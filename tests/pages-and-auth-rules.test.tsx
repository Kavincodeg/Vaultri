import { describe, it, expect, vi, beforeEach } from 'vitest'
import fs from 'fs'
import path from 'path'
import * as navigation from 'next/navigation'
import * as sessionModule from '@/lib/core-api/session'
import * as serverModule from '@/lib/core-api/server'

// Mock next-auth
const mockGetServerSession = vi.fn()
vi.mock('next-auth', () => ({
  getServerSession: () => mockGetServerSession(),
}))

vi.mock('next/navigation', () => ({
  redirect: vi.fn((url: string) => {
    const error: any = new Error('NEXT_REDIRECT')
    error.digest = `NEXT_REDIRECT;replace;${url};307;`
    error.url = url
    throw error
  }),
  notFound: vi.fn(() => {
    const error: any = new Error('NEXT_NOT_FOUND')
    error.digest = 'NEXT_NOT_FOUND'
    throw error
  }),
}))

import DashboardPage from '@/app/dashboard/page'
import DealsPage from '@/app/dashboard/deals/page'
import DealDetailPage from '@/app/dashboard/[id]/page'
import PaymentsPage from '@/app/dashboard/payments/page'
import RemindersPage from '@/app/dashboard/reminders/page'
import ActivityPage from '@/app/dashboard/activity/page'

describe('Server Architecture & Dashboard Pages Security Guarding', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    mockGetServerSession.mockReset()
  })

  describe('No server file calls core-api /auth/refresh', () => {
    it('verifies that no server file outside of NextAuth authOptions jwt callback calls core-api /auth/refresh', () => {
      const rootDir = path.resolve(__dirname, '..')
      const targetDirs = ['app', 'lib']
      const violatingFiles: string[] = []

      function scan(dir: string) {
        const entries = fs.readdirSync(dir, { withFileTypes: true })
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name)
          if (entry.isDirectory()) {
            if (entry.name !== 'node_modules' && entry.name !== '.next') {
              scan(fullPath)
            }
          } else if (entry.isFile() && (entry.name.endsWith('.ts') || entry.name.endsWith('.tsx'))) {
            const relPath = path.relative(rootDir, fullPath).replace(/\\/g, '/')
            // lib/auth.ts contains the NextAuth jwt callback which handles trigger: 'update'
            if (relPath === 'lib/auth.ts') continue

            const content = fs.readFileSync(fullPath, 'utf-8')
            // Match calls or path strings targeting the core-api refresh endpoint, distinguishing from /auth/refreshing
            if (
              content.match(/['"`]\/api\/v1\/auth\/refresh['"`]/) ||
              content.match(/path:\s*['"`]\/api\/v1\/auth\/refresh['"`]/) ||
              content.match(/fetch\([^)]*\/auth\/refresh(?!\w)/)
            ) {
              violatingFiles.push(relPath)
            }
          }
        }
      }

      for (const d of targetDirs) {
        scan(path.join(rootDir, d))
      }

      expect(violatingFiles).toEqual([])
    })
  })

  describe('Dashboard Pages Authentication & NeedsRefresh Redirects', () => {
    const testCases = [
      {
        name: 'DashboardPage (/dashboard)',
        invoke: () => DashboardPage(),
        expectedNext: '/dashboard',
      },
      {
        name: 'DealsPage (/dashboard/deals)',
        invoke: () => DealsPage(),
        expectedNext: '/dashboard/deals',
      },
      {
        name: 'DealDetailPage (/dashboard/[id])',
        invoke: () => DealDetailPage({ params: { id: 'deal-test-123' } }),
        expectedNext: '/dashboard/deal-test-123',
      },
      {
        name: 'PaymentsPage (/dashboard/payments)',
        invoke: () => PaymentsPage(),
        expectedNext: '/dashboard/payments',
      },
      {
        name: 'RemindersPage (/dashboard/reminders)',
        invoke: () => RemindersPage(),
        expectedNext: '/dashboard/reminders',
      },
      {
        name: 'ActivityPage (/dashboard/activity)',
        invoke: () => ActivityPage(),
        expectedNext: '/dashboard/activity',
      },
    ]

    for (const tc of testCases) {
      describe(tc.name, () => {
        it('redirects to /auth/signin when session?.user is missing or session has error flag', async () => {
          mockGetServerSession.mockResolvedValue(null)

          await expect(tc.invoke()).rejects.toThrow('NEXT_REDIRECT')
          expect(navigation.redirect).toHaveBeenCalledWith('/auth/signin')
        })

        it(`redirects to /auth/refreshing?next=${encodeURIComponent(tc.expectedNext)} on NeedsRefreshError`, async () => {
          mockGetServerSession.mockResolvedValue({
            user: { email: 'seller@example.com', name: 'Seller' },
          })
          vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(
            new sessionModule.NeedsRefreshError()
          )

          await expect(tc.invoke()).rejects.toThrow('NEXT_REDIRECT')
          expect(navigation.redirect).toHaveBeenCalledWith(
            `/auth/refreshing?next=${encodeURIComponent(tc.expectedNext)}`
          )
        })
      })
    }
  })
})
