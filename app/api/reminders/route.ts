import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { exec_send_reminder } from '@/lib/gemini-agent'

export async function POST(req: NextRequest) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    const { reminderId } = await req.json()
    if (!reminderId) return NextResponse.json({ error: 'Missing reminderId' }, { status: 400 })
    const result = await exec_send_reminder({ reminderId })
    return NextResponse.json(result)
  } catch (err: any) {
    console.error('[/api/reminders]', err)
    return NextResponse.json({ error: err.message }, { status: 500 })
  }
}