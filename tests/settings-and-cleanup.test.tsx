import { describe, it, expect, vi, beforeEach } from 'vitest'
import fs from 'fs'
import path from 'path'
import * as navigation from 'next/navigation'
import * as sessionModule from '@/lib/core-api/session'
import * as serverModule from '@/lib/core-api/server'
import React from 'react'

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

describe('Part B: Deletions, Cleanup, and Settings Page Migration', () => {
  const rootDir = path.resolve(__dirname, '..')

  beforeEach(() => {
    vi.restoreAllMocks()
    vi.clearAllMocks()
    mockGetServerSession.mockReset()
  })

  describe('Deleted files and QStash/queue cleanup assertions', () => {
    it('verifies deleted files are gone from the repository', () => {
      const deletedFiles = [
        'app/api/cron/check-reminders/route.ts',
        'worker.ts',
        'lib/queue.ts',
      ]
      for (const file of deletedFiles) {
        const fullPath = path.join(rootDir, file)
        expect(fs.existsSync(fullPath), `Expected ${file} to be deleted`).toBe(false)
      }
    })

    it('verifies that the "worker" npm script is removed from package.json', () => {
      const pkg = JSON.parse(fs.readFileSync(path.join(rootDir, 'package.json'), 'utf-8'))
      expect(pkg.scripts?.worker).toBeUndefined()
    })

    it('verifies that nothing in app/, components/, or lib/ imports lib/queue or @upstash/qstash', () => {
      const targetDirs = ['app', 'components', 'lib']
      const violatingImports: { file: string; match: string }[] = []

      function scan(dir: string) {
        if (!fs.existsSync(dir)) return
        const entries = fs.readdirSync(dir, { withFileTypes: true })
        for (const entry of entries) {
          const fullPath = path.join(dir, entry.name)
          if (entry.isDirectory()) {
            scan(fullPath)
          } else if (entry.isFile() && (entry.name.endsWith('.ts') || entry.name.endsWith('.tsx'))) {
            const relPath = path.relative(rootDir, fullPath).replace(/\\/g, '/')
            const content = fs.readFileSync(fullPath, 'utf-8')
            if (content.match(/['"`].*lib\/queue(\.ts)?['"`]/) || content.match(/@\/lib\/queue/)) {
              violatingImports.push({ file: relPath, match: 'lib/queue import' })
            }
            if (content.match(/@upstash\/qstash/)) {
              violatingImports.push({ file: relPath, match: '@upstash/qstash import' })
            }
          }
        }
      }

      for (const d of targetDirs) {
        scan(path.join(rootDir, d))
      }

      expect(violatingImports).toEqual([])
    })

    it('verifies /api/settings/status does not return a qstash entry', async () => {
      process.env.RAZORPAY_KEY_ID = 'test_key_id'
      process.env.RAZORPAY_KEY_SECRET = 'test_key_secret'
      mockGetServerSession.mockResolvedValue({
        user: { email: 'seller@example.com', name: 'Seller' },
      })
      const { GET } = await import('@/app/api/settings/status/route')
      const res = await GET()
      const data = await res.json()
      expect(data.qstash).toBeUndefined()
    })

    it('verifies next.config.js does not set ignoreBuildErrors or ignoreDuringBuilds to true', () => {
      const nextConfigPath = path.join(rootDir, 'next.config.js')
      const content = fs.readFileSync(nextConfigPath, 'utf-8')
      expect(content).not.toMatch(/ignoreBuildErrors:\s*true/)
      expect(content).not.toMatch(/ignoreDuringBuilds:\s*true/)
    })
  })

  describe('Settings Page Migration to Core-API /api/v1/me', () => {
    it('redirects to /auth/signin when session?.user is missing', async () => {
      mockGetServerSession.mockResolvedValue(null)
      const SettingsPage = (await import('@/app/dashboard/settings/page')).default

      await expect(SettingsPage()).rejects.toMatchObject({
        url: '/auth/signin',
      })
    })

    it('redirects to /auth/signin when session has error flag', async () => {
      mockGetServerSession.mockResolvedValue({
        user: undefined,
        error: true,
      })
      const SettingsPage = (await import('@/app/dashboard/settings/page')).default

      await expect(SettingsPage()).rejects.toMatchObject({
        url: '/auth/signin',
      })
    })

    it('redirects to /auth/refreshing?next=%2Fdashboard%2Fsettings on NeedsRefreshError', async () => {
      mockGetServerSession.mockResolvedValue({
        user: { name: 'Seller', email: 'seller@example.com' },
      })
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockRejectedValue(new sessionModule.NeedsRefreshError())
      const SettingsPage = (await import('@/app/dashboard/settings/page')).default

      await expect(SettingsPage()).rejects.toMatchObject({
        url: '/auth/refreshing?next=' + encodeURIComponent('/dashboard/settings'),
      })
    })

    it('renders profile correctly from mocked /api/v1/me and removes createdAt display and Prisma import', async () => {
      // 1. Verify no Prisma import exists in app/dashboard/settings/page.tsx
      const settingsPageSource = fs.readFileSync(path.join(rootDir, 'app/dashboard/settings/page.tsx'), 'utf-8')
      expect(settingsPageSource).not.toMatch(/@\/lib\/prisma/)
      expect(settingsPageSource).not.toMatch(/prisma/i)
      expect(settingsPageSource).not.toMatch(/createdAt/i)

      mockGetServerSession.mockResolvedValue({
        user: { name: 'Seller User', email: 'seller@vaultri.com' },
      })
      vi.spyOn(sessionModule, 'getAccessTokenOrThrow').mockResolvedValue('valid-access-token')
      const coreApiSpy = vi.spyOn(serverModule, 'coreApiFetch').mockResolvedValue({
        id: '123e4567-e89b-12d3-a456-426614174000',
        email: 'seller@vaultri.com',
        name: 'Seller User',
        phone: '+919876543210',
        plan: 'pro',
      })

      const SettingsPage = (await import('@/app/dashboard/settings/page')).default
      const jsx = await SettingsPage()

      expect(coreApiSpy).toHaveBeenCalledWith({
        path: '/api/v1/me',
        token: 'valid-access-token',
      })

      // Verify returned element is valid React element
      expect(React.isValidElement(jsx)).toBe(true)
    })
  })
})
