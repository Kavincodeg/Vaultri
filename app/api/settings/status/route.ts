import { NextRequest, NextResponse } from 'next/server'
import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { razorpay } from '@/lib/razorpay'
import nodemailer from 'nodemailer'

export async function GET() {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }

    const razorpayKeyId = process.env.RAZORPAY_KEY_ID ?? ''
    const gmailUser = process.env.GMAIL_USER ?? ''
    const geminiKey = process.env.GEMINI_API_KEY ?? ''
    const qstashToken = process.env.QSTASH_TOKEN ?? ''
    const appUrl = process.env.NEXT_PUBLIC_APP_URL ?? 'https://vaultri.vercel.app'

    return NextResponse.json({
      razorpay: {
        configured: Boolean(razorpayKeyId && process.env.RAZORPAY_KEY_SECRET),
        keyIdPrefix: razorpayKeyId ? `${razorpayKeyId.slice(0, 8)}...` : 'Not configured',
        webhookSecretConfigured: Boolean(process.env.RAZORPAY_WEBHOOK_SECRET),
        webhookUrl: `${appUrl.replace(/\/$/, '')}/api/webhook`,
      },
      email: {
        configured: Boolean(gmailUser && process.env.GMAIL_APP_PASSWORD),
        sender: gmailUser || 'Not configured',
        service: 'Gmail SMTP',
      },
      ai: {
        configured: Boolean(geminiKey),
        model: 'Gemini 2.0 Flash',
      },
      qstash: {
        configured: Boolean(qstashToken || process.env.QSTASH_CURRENT_SIGNING_KEY),
        cronUrl: `${appUrl.replace(/\/$/, '')}/api/cron/check-reminders`,
      },
    })
  } catch (err: any) {
    console.error('[/api/settings/status GET]', err)
    return NextResponse.json({ error: err.message ?? 'Internal error' }, { status: 500 })
  }
}

export async function POST(req: NextRequest) {
  try {
    const session = await getServerSession(authOptions)
    if (!session?.user?.email) {
      return NextResponse.json({ error: 'Unauthorized' }, { status: 401 })
    }

    const { action } = await req.json()

    if (action === 'test_email') {
      const user = process.env.GMAIL_USER
      const pass = process.env.GMAIL_APP_PASSWORD?.replace(/\s+/g, '')

      if (!user || !pass) {
        return NextResponse.json({
          success: false,
          message: 'Missing GMAIL_USER or GMAIL_APP_PASSWORD in environment variables.',
        })
      }

      const transporter = nodemailer.createTransport({
        service: 'gmail',
        auth: { user, pass },
      })

      await new Promise<void>((resolve, reject) => {
        transporter.verify((err) => {
          if (err) reject(err)
          else resolve()
        })
      })

      return NextResponse.json({
        success: true,
        message: `Gmail SMTP connected successfully for ${user}!`,
      })
    }

    if (action === 'test_razorpay') {
      if (!process.env.RAZORPAY_KEY_ID || !process.env.RAZORPAY_KEY_SECRET) {
        return NextResponse.json({
          success: false,
          message: 'Missing RAZORPAY_KEY_ID or RAZORPAY_KEY_SECRET in environment variables.',
        })
      }

      // Test fetching payments list to verify credentials
      await (razorpay.payments as any).all({ count: 1 })

      return NextResponse.json({
        success: true,
        message: 'Razorpay API credentials verified successfully!',
      })
    }

    return NextResponse.json({ error: 'Invalid action' }, { status: 400 })
  } catch (err: any) {
    console.error('[/api/settings/status POST]', err)
    return NextResponse.json({
      success: false,
      message: err.message ?? 'Verification failed',
    })
  }
}
