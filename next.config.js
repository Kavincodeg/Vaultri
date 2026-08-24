// @ts-check
const { withSentryConfig } = require('@sentry/nextjs')

/** @type {import("next").NextConfig} */
const nextConfig = {
  eslint: {
    ignoreDuringBuilds: true,
  },
  typescript: {
    ignoreBuildErrors: true,
  },
  experimental: {
    // Tell webpack not to bundle these Node.js-only packages —
    // they are available at runtime in the Node.js server environment.
    // Key for Next.js 14 (renamed to serverExternalPackages in Next.js 15+)
    serverComponentsExternalPackages: ['bullmq', 'ioredis'],
  },
}

module.exports = withSentryConfig(nextConfig, {
  silent: true,
  widenClientFileUpload: true,
})
