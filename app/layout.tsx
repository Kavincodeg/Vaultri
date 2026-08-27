import type { Metadata } from "next"
import "./globals.css"
import { Nav } from "@/components/Nav"
import { Providers } from "@/components/Providers"
import { LayoutWrapper } from "@/components/LayoutWrapper"

export const metadata: Metadata = {
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

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>
          <LayoutWrapper>
            <Nav />
            <main>{children}</main>
          </LayoutWrapper>
        </Providers>
      </body>
    </html>
  )
}
