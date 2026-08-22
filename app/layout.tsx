import type { Metadata } from "next"
import "./globals.css"
import { Nav } from "@/components/Nav"
import { Providers } from "@/components/Providers"

export const metadata: Metadata = {
  title: "Seller Protection Agent",
  description: "AI-powered contract, deposit, and reminder system for independent sellers. Built for Razorpay AI Buildathon.",
}

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>
        <Providers>
          <Nav />
          <main>{children}</main>
        </Providers>
      </body>
    </html>
  )
}
