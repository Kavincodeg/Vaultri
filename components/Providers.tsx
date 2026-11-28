"use client"

import React from "react"
import { SessionProvider } from "next-auth/react"
import type { Session } from "next-auth"
import { ThemeProvider } from "./ThemeProvider"
import { SessionKeepAlive } from "./SessionKeepAlive"

interface ProvidersProps {
  children: React.ReactNode
  session?: Session | null
}

/**
 * SessionProvider gets the server-fetched session as its `session` prop, with refetchInterval={0}
 * and refetchOnWindowFocus={false} (no client-side GET /api/auth/session at mount or focus, because a GET
 * that started before a refresh and finishes after it can overwrite the fresh cookie with the old, revoked refresh token).
 *
 * update() in SessionKeepAlive is the ONLY client call that touches the session.
 * No other client file calls getSession or triggers a session refetch.
 */
export function Providers({ children, session }: ProvidersProps) {
  return (
    <SessionProvider
      session={session}
      refetchInterval={0}
      refetchOnWindowFocus={false}
    >
      <SessionKeepAlive />
      <ThemeProvider>{children}</ThemeProvider>
    </SessionProvider>
  )
}
