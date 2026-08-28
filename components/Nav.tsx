'use client'
import { useSession } from 'next-auth/react'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { ShieldCheck } from 'lucide-react'
import { ThemeToggle } from './ThemeProvider'

export function Nav() {
  const { data: session } = useSession()
  const pathname = usePathname()

  // Only show logo on non-dashboard pages
  const showLogo = !pathname.startsWith('/dashboard')

  return (
    <nav className='nav'>
      <div className='nav-inner'>
        {showLogo && (
          <Link href='/' className='nav-brand' style={{ textDecoration: 'none' }}>
            <div className='nav-brand-icon'>
              <ShieldCheck size={18} color="#fff" strokeWidth={2.5} />
            </div>
            <span>VAULTRI</span>
          </Link>
        )}
        {!showLogo && session && (
          <div className='nav-brand' style={{ fontSize: '0.9rem', color: 'var(--text-muted)' }}>
            Good morning, <strong>{session.user?.name}</strong>
          </div>
        )}
        <div className='nav-actions'>
          <ThemeToggle />
          {session ? (
            <>
              {pathname.startsWith('/dashboard') && (
                <Link href='/dashboard/new' className='btn btn-primary btn-sm'>+ New Deal</Link>
              )}
              {!pathname.startsWith('/dashboard') && (
                <Link href='/dashboard' className='btn btn-ghost btn-sm'>Dashboard</Link>
              )}
              {!pathname.startsWith('/dashboard') && (
                <Link href='/auth/signin' className='btn btn-secondary btn-sm'>Sign in</Link>
              )}
            </>
          ) : (
            <>
              <Link href='/auth/signin' className='btn btn-secondary btn-sm'>Sign in</Link>
              <Link href='/auth/signup' className='btn btn-primary btn-sm'>Get Started</Link>
            </>
          )}
        </div>
      </div>
    </nav>
  )
}