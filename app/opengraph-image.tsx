import { ImageResponse } from 'next/og'

// Route segment config — tells Next.js this is a static OG image
export const runtime = 'edge'
export const alt = 'Vaultri — Every deal, protected.'
export const size = { width: 1200, height: 630 }
export const contentType = 'image/png'

export default function Image() {
  return new ImageResponse(
    (
      <div
        style={{
          width: '1200px',
          height: '630px',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          background: '#0B1220',
          fontFamily: 'Georgia, "Times New Roman", serif',
          position: 'relative',
          overflow: 'hidden',
        }}
      >
        {/* ── Subtle radial glow behind logo ─────────────── */}
        <div
          style={{
            position: 'absolute',
            top: '50%',
            left: '50%',
            transform: 'translate(-50%, -60%)',
            width: '560px',
            height: '560px',
            borderRadius: '50%',
            background:
              'radial-gradient(circle, rgba(201,162,39,0.12) 0%, rgba(11,18,32,0) 70%)',
            display: 'flex',
          }}
        />

        {/* ── Top border accent ──────────────────────────── */}
        <div
          style={{
            position: 'absolute',
            top: 0,
            left: 0,
            right: 0,
            height: '4px',
            background: 'linear-gradient(90deg, #C9A227, #e8c96b, #C9A227)',
            display: 'flex',
          }}
        />

        {/* ── Shield seal (SVG inline) ───────────────────── */}
        <div style={{ display: 'flex', marginBottom: '28px' }}>
          <svg
            width="90"
            height="100"
            viewBox="0 0 90 100"
            fill="none"
            xmlns="http://www.w3.org/2000/svg"
          >
            {/* Shield body */}
            <path
              d="M45 4L8 18V48C8 69 24 87 45 96C66 87 82 69 82 48V18L45 4Z"
              fill="#0B1220"
              stroke="#C9A227"
              strokeWidth="3"
            />
            {/* Inner shield highlight */}
            <path
              d="M45 14L16 26V48C16 65 28 80 45 88C62 80 74 65 74 48V26L45 14Z"
              fill="rgba(201,162,39,0.08)"
              stroke="rgba(201,162,39,0.3)"
              strokeWidth="1"
            />
            {/* Checkmark */}
            <path
              d="M29 50L40 61L61 39"
              stroke="#C9A227"
              strokeWidth="5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        </div>

        {/* ── Wordmark ───────────────────────────────────── */}
        <div
          style={{
            fontSize: '72px',
            fontWeight: '700',
            color: '#F0E6C8',
            letterSpacing: '-1px',
            lineHeight: 1,
            display: 'flex',
          }}
        >
          VAULTRI
        </div>

        {/* ── Brass rule ─────────────────────────────────── */}
        <div
          style={{
            width: '64px',
            height: '2px',
            background: '#C9A227',
            margin: '20px 0',
            display: 'flex',
          }}
        />

        {/* ── Tagline ────────────────────────────────────── */}
        <div
          style={{
            fontSize: '26px',
            color: '#94a3b8',
            fontStyle: 'italic',
            letterSpacing: '0.5px',
            display: 'flex',
          }}
        >
          Every deal, protected.
        </div>

        {/* ── Sub-caption ────────────────────────────────── */}
        <div
          style={{
            marginTop: '20px',
            fontSize: '16px',
            color: 'rgba(201,162,39,0.75)',
            letterSpacing: '2.5px',
            textTransform: 'uppercase',
            fontFamily: 'system-ui, sans-serif',
            fontWeight: '500',
            display: 'flex',
          }}
        >
          AI · Contracts · Deposits · Reminders
        </div>

        {/* ── Bottom border accent ───────────────────────── */}
        <div
          style={{
            position: 'absolute',
            bottom: 0,
            left: 0,
            right: 0,
            height: '2px',
            background: 'rgba(201,162,39,0.3)',
            display: 'flex',
          }}
        />

        {/* ── Corner seals ──────────────────────────────── */}
        <div
          style={{
            position: 'absolute',
            bottom: '24px',
            right: '40px',
            fontSize: '13px',
            color: 'rgba(201,162,39,0.5)',
            fontFamily: 'system-ui, sans-serif',
            letterSpacing: '1px',
            display: 'flex',
          }}
        >
          vaultri.app
        </div>
      </div>
    ),
    { ...size }
  )
}
