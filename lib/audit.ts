import { prisma } from '@/lib/prisma'

export async function logAudit(dealId: string, action: string, detail: string) {
  try {
    return await prisma.auditLog.create({
      data: { dealId, action, detail },
    })
  } catch (err) {
    console.error('[AuditLog error]', err)
  }
}