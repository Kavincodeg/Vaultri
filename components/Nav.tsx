"use client"
import { useSession, signIn, signOut } from "next-auth/react"
import Link from "next/link"

export function Nav() {
  const { data: session } = useSession()
  return (
    <nav className="nav">
      <div className="nav-inner">
        <Link href="/" className="nav-brand" style={{ textDecoration: "none" }}>
          <div className="nav-brand-icon">🛡️</div>
          <span>Seller Protection</span>
        </Link>
        <div className="nav-actions">
          {session ? (
            <>
              <Link href="/dashboard" className="btn btn-ghost btn-sm">Dashboard</Link>
              <Link href="/dashboard/new" className="btn btn-primary btn-sm">+ New Deal</Link>
              <button
                id="btn-signout"
                className="btn btn-secondary btn-sm"
                onClick={() => signOut()}
              >
                Sign out
              </button>
            </>
          ) : (
            <button
              id="btn-signin"
              className="btn btn-primary btn-sm"
              onClick={() => signIn()}
            >
              Sign in
            </button>
          )}
        </div>
      </div>
    </nav>
  )
}
