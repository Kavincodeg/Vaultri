'use client'
import Link from 'next/link'
import { usePathname } from 'next/navigation'
import { signOut } from 'next-auth/react'
import { useTheme } from './ThemeProvider'
import { Sun, Moon } from 'lucide-react'

export function Sidebar() {
  const pathname = usePathname()
  const { theme, toggleTheme } = useTheme()

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

      <div className='sidebar-footer' style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
        <button
          onClick={toggleTheme}
          className='sidebar-nav-item'
          style={{ width: '100%', justifyContent: 'flex-start', color: 'var(--text-muted)' }}
          title={`Switch to ${theme === 'dark' ? 'Bright mode' : 'Dark mode'}`}
        >
          <span className='sidebar-nav-icon'>
            {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
          </span>
          <span>{theme === 'dark' ? 'Bright Mode' : 'Dark Mode'}</span>
        </button>
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
