'use client'
import { useSession } from 'next-auth/react'
import { usePathname } from 'next/navigation'
import { Sidebar } from '@/components/Sidebar'

export function LayoutWrapper({ children }: { children: React.ReactNode }) {
  const { data: session } = useSession()
  const pathname = usePathname()

  // Only show sidebar on dashboard pages for authenticated users with a usable session
  const showSidebar = !!session?.user && (pathname.startsWith('/dashboard') || pathname.startsWith('/profile'))

  return (
    <div id='root-layout'>
      {showSidebar && <Sidebar />}
      <div className={`main-content${showSidebar ? ' has-sidebar' : ''}`}>{children}</div>
    </div>
  )
}
