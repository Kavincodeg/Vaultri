'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { signOut } from 'next-auth/react'

export function Sidebar() {
  const pathname = usePathname()

  const isActive = (href: string) => {
    if (href === '/dashboard' && pathname === '/dashboard') return true
    if (href !== '/dashboard' && pathname.startsWith(href)) return true
    return false
  }

  const navItems = [
    { href: '/dashboard', icon: '📊', label: 'Overview' },
    { href: '/dashboard/deals', icon: '🤝', label: 'Deals' },
    { href: '/dashboard/payments', icon: '💳', label: 'Payments' },
    { href: '/dashboard/reminders', icon: '🔔', label: 'Reminders' },
    { href: '/dashboard/activity', icon: '📝', label: 'Activity' },
    { href: '/dashboard/settings', icon: '⚙️', label: 'Settings' },
  ]

  return (
    <div className='sidebar'>
      <div className='sidebar-header'>
        <div className='sidebar-logo'>🛡️</div>
        <div className='sidebar-brand'>VAULTRI</div>
      </div>

      <nav className='sidebar-nav'>
        {navItems.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={`sidebar-nav-item ${isActive(item.href) ? 'active' : ''}`}
          >
            <span className='sidebar-nav-icon'>{item.icon}</span>
            <span>{item.label}</span>
          </Link>
        ))}
      </nav>

      <div className='sidebar-footer'>
        <button
          onClick={() => signOut({ redirect: true, callbackUrl: '/' })}
          className='sidebar-nav-item'
          style={{ width: '100%', justifyContent: 'flex-start', color: 'var(--text-muted)' }}
        >
          <span className='sidebar-nav-icon'>🚪</span>
          <span>Logout</span>
        </button>
      </div>
    </div>
  )
}
