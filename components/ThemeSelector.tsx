'use client'
import { useTheme } from './ThemeProvider'
import { Sun, Moon, Monitor } from 'lucide-react'

export function ThemeSelector() {
  const { theme, setTheme } = useTheme()

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '12px', marginTop: '8px' }}>
      <button
        type="button"
        onClick={() => setTheme('light')}
        className={`theme-select-card ${theme === 'light' ? 'selected' : ''}`}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '10px',
          padding: '12px 16px',
          borderRadius: 'var(--radius-sm)',
          border: theme === 'light' ? '2px solid var(--accent)' : '1px solid var(--border)',
          background: theme === 'light' ? 'var(--accent-muted)' : 'var(--surface2)',
          color: theme === 'light' ? 'var(--accent)' : 'var(--text)',
          fontWeight: 600,
          fontSize: '0.875rem',
          cursor: 'pointer',
          transition: 'all 0.15s ease',
        }}
      >
        <Sun size={18} />
        <span>Bright Mode</span>
      </button>

      <button
        type="button"
        onClick={() => setTheme('dark')}
        className={`theme-select-card ${theme === 'dark' ? 'selected' : ''}`}
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: '10px',
          padding: '12px 16px',
          borderRadius: 'var(--radius-sm)',
          border: theme === 'dark' ? '2px solid var(--accent)' : '1px solid var(--border)',
          background: theme === 'dark' ? 'var(--accent-muted)' : 'var(--surface2)',
          color: theme === 'dark' ? 'var(--accent)' : 'var(--text)',
          fontWeight: 600,
          fontSize: '0.875rem',
          cursor: 'pointer',
          transition: 'all 0.15s ease',
        }}
      >
        <Moon size={18} />
        <span>Dark Mode</span>
      </button>
    </div>
  )
}
