'use client'
import { createContext, useContext, useEffect, useState } from 'react'
import { Sun, Moon } from 'lucide-react'

type Theme = 'dark' | 'light'

interface ThemeContextType {
  theme: Theme
  toggleTheme: () => void
  setTheme: (theme: Theme) => void
}

const ThemeContext = createContext<ThemeContextType>({
  theme: 'dark',
  toggleTheme: () => {},
  setTheme: () => {},
})

export function useTheme() {
  return useContext(ThemeContext)
}

export function ThemeProvider({ children }: { children: React.ReactNode }) {
  const [theme, setThemeState] = useState<Theme>('dark')
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    // Read from localStorage or system preference on mount
    const stored = localStorage.getItem('vaultri-theme') as Theme | null
    if (stored === 'light' || stored === 'dark') {
      setThemeState(stored)
      document.documentElement.setAttribute('data-theme', stored)
    } else {
      const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
      const initial: Theme = prefersDark ? 'dark' : 'light'
      setThemeState(initial)
      document.documentElement.setAttribute('data-theme', initial)
    }
    setMounted(true)
  }, [])

  const setTheme = (newTheme: Theme) => {
    setThemeState(newTheme)
    document.documentElement.setAttribute('data-theme', newTheme)
    localStorage.setItem('vaultri-theme', newTheme)
  }

  const toggleTheme = () => {
    const nextTheme = theme === 'dark' ? 'light' : 'dark'
    setTheme(nextTheme)
  }

  return (
    <ThemeContext.Provider value={{ theme, toggleTheme, setTheme }}>
      {children}
    </ThemeContext.Provider>
  )
}

export function ThemeToggle({ className = '', showLabel = false }: { className?: string; showLabel?: boolean }) {
  const { theme, toggleTheme } = useTheme()
  const [mounted, setMounted] = useState(false)

  useEffect(() => {
    setMounted(true)
  }, [])

  if (!mounted) {
    return (
      <button
        type="button"
        aria-label="Toggle theme"
        className={`theme-toggle ${className}`}
        disabled
        style={{ opacity: 0.6 }}
      >
        <Moon size={17} />
        {showLabel && <span>Theme</span>}
      </button>
    )
  }

  const isDark = theme === 'dark'

  return (
    <button
      type="button"
      onClick={toggleTheme}
      aria-label={`Switch to ${isDark ? 'Bright (Light)' : 'Dark'} mode`}
      title={`Switch to ${isDark ? 'Bright (Light) mode' : 'Dark mode'}`}
      className={`theme-toggle ${className}`}
    >
      {isDark ? (
        <>
          <Sun size={17} className="theme-toggle-icon sun-icon" />
          {showLabel && <span>Bright Mode</span>}
        </>
      ) : (
        <>
          <Moon size={17} className="theme-toggle-icon moon-icon" />
          {showLabel && <span>Dark Mode</span>}
        </>
      )}
    </button>
  )
}
