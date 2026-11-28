import type { Metadata } from "next"
import "./globals.css"
import { getServerSession } from "next-auth"
import { authOptions } from "@/lib/auth"
import { Nav } from "@/components/Nav"
import { Providers } from "@/components/Providers"
import { LayoutWrapper } from "@/components/LayoutWrapper"

export const metadata: Metadata = {
  metadataBase: new URL(process.env.NEXT_PUBLIC_APP_URL || 'https://vaultri.vercel.app'),
  title: 'Vaultri — Every deal, protected.',
  description: 'AI-powered contract, deposit, and reminder system for independent sellers.',
  openGraph: {
    title: 'Vaultri — Every deal, protected.',
    description: 'AI-powered contract, deposit, and reminder system for independent sellers.',
    siteName: 'Vaultri',
    locale: 'en_IN',
    type: 'website',
  },
  twitter: {
    card: 'summary_large_image',
    title: 'Vaultri — Every deal, protected.',
    description: 'AI-powered contract, deposit, and reminder system for independent sellers.',
  },
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const session = await getServerSession(authOptions)

  return (
    <html lang="en">
      <body>
        <Providers session={session}>
          <LayoutWrapper>
            <Nav />
            <main>{children}</main>
          </LayoutWrapper>
        </Providers>
      </body>
    </html>
  )
}
