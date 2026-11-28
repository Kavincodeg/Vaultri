import { getServerSession } from 'next-auth'
import { authOptions } from '@/lib/auth'
import { redirect } from 'next/navigation'
import { getAccessTokenOrThrow, NeedsRefreshError } from '@/lib/core-api/session'
import { coreApiFetch } from '@/lib/core-api/server'
import { ThemeSelector } from '@/components/ThemeSelector'
import { ApiConfigSection } from '@/components/ApiConfigSection'

interface SellerProfile {
  id: string
  email: string
  name: string
  phone?: string | null
  plan: string
}

export default async function SettingsPage() {
  const session = await getServerSession(authOptions)
  if (!session?.user || (session as any).error) redirect('/auth/signin')

  let token: string
  try {
    token = await getAccessTokenOrThrow()
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/settings'))
    }
    redirect('/auth/signin')
  }

  let seller: SellerProfile
  try {
    seller = await coreApiFetch<SellerProfile>({
      path: '/api/v1/me',
      token,
    })
  } catch (err) {
    if (err instanceof NeedsRefreshError) {
      redirect('/auth/refreshing?next=' + encodeURIComponent('/dashboard/settings'))
    }
    redirect('/auth/signin')
  }

  return (
    <div className='container' style={{ paddingTop: '32px', paddingBottom: '48px' }}>
      {/* Header */}
      <div className='dashboard-header'>
        <div>
          <div className='dashboard-title'>Settings</div>
          <div className='dashboard-subtitle'>Manage your account and preferences</div>
        </div>
      </div>

      {/* Settings Sections */}
      <div style={{ maxWidth: '600px' }}>
        {/* Appearance Section */}
        <div className='card' style={{ marginBottom: '24px' }}>
          <div className='card-header'>
            <div className='card-title'>🎨 Appearance & Theme</div>
          </div>
          <div className='form-group' style={{ gap: '10px' }}>
            <div className='form-label'>Theme Mode</div>
            <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>
              Choose your preferred interface theme. Vaultri automatically adjusts all colors and styles.
            </div>
            <ThemeSelector />
          </div>
        </div>

        {/* Profile Section */}
        <div className='card' style={{ marginBottom: '24px' }}>
          <div className='card-header'>
            <div className='card-title'>👤 Profile Information</div>
          </div>
          <div className='form-group' style={{ gap: '16px' }}>
            <div>
              <div className='form-label'>Name</div>
              <div style={{ padding: '10px 14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }}>
                {seller.name}
              </div>
            </div>
            <div>
              <div className='form-label'>Email</div>
              <div style={{ padding: '10px 14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }}>
                {seller.email}
              </div>
            </div>
            <div>
              <div className='form-label'>Phone</div>
              <div style={{ padding: '10px 14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', color: 'var(--text)' }}>
                {seller.phone || 'Not provided'}
              </div>
            </div>
            <div>
              <div className='form-label'>Plan</div>
              <div style={{ padding: '10px 14px', background: 'var(--surface2)', borderRadius: 'var(--radius-sm)', color: 'var(--accent)' }}>
                {(seller.plan || 'free').toUpperCase()} Plan
              </div>
            </div>
          </div>
        </div>

        {/* Preferences Section */}
        <div className='card' style={{ marginBottom: '24px' }}>
          <div className='card-header'>
            <div className='card-title'>⚙️ Preferences</div>
          </div>
          <div className='form-group' style={{ gap: '16px' }}>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px',
                background: 'var(--surface2)',
                borderRadius: 'var(--radius-sm)',
              }}
            >
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text)' }}>Email Notifications</div>
                <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>Get alerts on reminder sending</div>
              </div>
              <input type='checkbox' defaultChecked style={{ cursor: 'pointer' }} />
            </div>
            <div
              style={{
                display: 'flex',
                alignItems: 'center',
                justifyContent: 'space-between',
                padding: '12px',
                background: 'var(--surface2)',
                borderRadius: 'var(--radius-sm)',
              }}
            >
              <div>
                <div style={{ fontWeight: 500, color: 'var(--text)' }}>Automatic Reminders</div>
                <div style={{ fontSize: '0.8125rem', color: 'var(--text-muted)' }}>Automatically send payment reminders</div>
              </div>
              <input type='checkbox' style={{ cursor: 'pointer' }} />
            </div>
          </div>
        </div>

        {/* API Section */}
        <ApiConfigSection />

        {/* Danger Zone */}
        <div className='card' style={{ marginBottom: '24px', borderColor: 'var(--danger)' }}>
          <div className='card-header'>
            <div className='card-title' style={{ color: 'var(--danger)' }}>🚨 Danger Zone</div>
          </div>
          <div className='form-group' style={{ gap: '12px' }}>
            <div style={{ fontSize: '0.875rem', color: 'var(--text-muted)' }}>
              These actions cannot be undone. Please proceed with caution.
            </div>
            <button className='btn btn-danger' style={{ width: '100%' }}>
              Delete Account
            </button>
          </div>
        </div>

        {/* Support Info Section */}
        <div className='alert alert-info'>
          <span>ℹ️</span>
          <div>
            Need help? Contact support at support@vaultri.com
          </div>
        </div>
      </div>
    </div>
  )
}
