import { getServerSession } from "next-auth"
import { redirect } from "next/navigation"
import { prisma } from "@/lib/prisma"
import Link from "next/link"

function statusBadge(status: string) {
  const map: Record<string, string> = {
    draft: "badge-draft",
    deposit_pending: "badge-pending",
    in_progress: "badge-progress",
    completed: "badge-completed",
    cancelled: "badge-cancelled",
  }
  const label: Record<string, string> = {
    draft: "Draft",
    deposit_pending: "Deposit Pending",
    in_progress: "In Progress",
    completed: "Completed",
    cancelled: "Cancelled",
  }
  return { cls: map[status] ?? "badge-draft", label: label[status] ?? status }
}

function formatRupees(paise: number) {
  return "₹" + (paise / 100).toLocaleString("en-IN")
}

export default async function DashboardPage() {
  const session = await getServerSession()
  if (!session?.user?.email) redirect("/auth/signin")

  const seller = await prisma.seller.findUnique({
    where: { email: session.user.email },
    include: {
      deals: {
        orderBy: { createdAt: "desc" },
        include: { payments: true },
      },
    },
  })

  if (!seller) redirect("/auth/signin")

  const deals = seller.deals

  return (
    <div className="container">
      <div className="page-header" style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", flexWrap: "wrap", gap: "16px" }}>
        <div>
          <h1 className="page-title">Your Deals</h1>
          <p className="page-sub">Welcome back, {seller.name}</p>
        </div>
        <Link href="/dashboard/new" className="btn btn-primary" id="btn-new-deal">
          + Create New Deal
        </Link>
      </div>

      {deals.length === 0 ? (
        <div className="empty-state">
          <div className="empty-icon">📋</div>
          <div className="empty-title">No deals yet</div>
          <div className="empty-desc">Create your first deal to get a contract drafted and deposit link generated automatically.</div>
          <Link href="/dashboard/new" className="btn btn-primary">Create your first deal →</Link>
        </div>
      ) : (
        <div className="stack" style={{ paddingBottom: "48px" }}>
          {deals.map((deal) => {
            const { cls, label } = statusBadge(deal.status)
            const depositPayment = deal.payments.find((p) => p.type === "deposit")
            return (
              <div key={deal.id} className="deal-card">
                <div className="deal-card-header">
                  <div>
                    <div className="deal-customer">{deal.customerName}</div>
                  </div>
                  <span className={`badge ${cls}`}>{label}</span>
                </div>
                <div className="deal-description">{deal.description}</div>
                <div className="deal-meta">
                  <span className="deal-meta-item">
                    <strong>{formatRupees(deal.price)}</strong> total
                  </span>
                  <span className="deal-meta-item">
                    <strong>{deal.depositPercent}%</strong> deposit
                    ({formatRupees(Math.round(deal.price * deal.depositPercent / 100))})
                  </span>
                  <span className="deal-meta-item">
                    Due <strong>{new Date(deal.dueDate).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" })}</strong>
                  </span>
                  {depositPayment && (
                    <span className="deal-meta-item">
                      Deposit: <span className={`badge ${depositPayment.status === "paid" ? "badge-paid" : depositPayment.status === "expired" ? "badge-expired" : "badge-created"}`}>{depositPayment.status}</span>
                    </span>
                  )}
                </div>
                <div className="deal-actions">
                  <Link href={`/dashboard/${deal.id}`} className="btn btn-secondary btn-sm" id={`btn-view-${deal.id}`}>
                    View details →
                  </Link>
                  {depositPayment?.shortUrl && (
                    <a
                      href={depositPayment.shortUrl}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="btn btn-ghost btn-sm"
                      id={`btn-payment-link-${deal.id}`}
                    >
                      💳 Payment link
                    </a>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
