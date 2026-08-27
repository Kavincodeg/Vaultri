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
    // Tell webpack not to bundle Node.js-only packages.
    // Key for Next.js 14 (renamed to serverExternalPackages in Next.js 15+)
    serverComponentsExternalPackages: [],
  },
}

module.exports = withSentryConfig(nextConfig, {
  silent: true,
  widenClientFileUpload: true,
})
