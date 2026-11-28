export type CoreApiDealStatus =
  | 'DRAFT'
  | 'DEPOSIT_PENDING'
  | 'DEPOSIT_PAID'
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'CANCELLED'

export type UiDealStatus =
  | 'draft'
  | 'deposit_pending'
  | 'deposit_paid'
  | 'in_progress'
  | 'completed'
  | 'cancelled'

export type CoreApiPaymentStatus =
  | 'PENDING'
  | 'PAID'
  | 'CANCELLED'
  | 'EXPIRED'
  | 'FAILED'

export type UiPaymentStatus =
  | 'created'
  | 'completed'
  | 'cancelled'
  | 'expired'
  | 'failed'

export type CoreApiPaymentType = 'DEPOSIT' | 'CANCELLATION_FEE'
export type UiPaymentType = 'deposit' | 'cancellation_fee'

export type CoreApiReminderStatus = 'PENDING' | 'SENT' | 'FAILED' | 'CANCELLED'
export type UiReminderStatus = 'scheduled' | 'sent' | 'failed' | 'cancelled'

const DEAL_STATUS_MAP: Record<string, UiDealStatus> = {
  DRAFT: 'draft',
  DEPOSIT_PENDING: 'deposit_pending',
  DEPOSIT_PAID: 'deposit_paid',
  IN_PROGRESS: 'in_progress',
  COMPLETED: 'completed',
  CANCELLED: 'cancelled',
}

const PAYMENT_STATUS_MAP: Record<string, UiPaymentStatus> = {
  PENDING: 'created',
  PAID: 'completed',
  CANCELLED: 'cancelled',
  EXPIRED: 'expired',
  FAILED: 'failed',
}

const PAYMENT_TYPE_MAP: Record<string, UiPaymentType> = {
  DEPOSIT: 'deposit',
  CANCELLATION_FEE: 'cancellation_fee',
}

const REMINDER_STATUS_MAP: Record<string, UiReminderStatus> = {
  PENDING: 'scheduled',
  SENT: 'sent',
  FAILED: 'failed',
  CANCELLED: 'cancelled',
}

export function mapDealStatus(status: CoreApiDealStatus | string | null | undefined): UiDealStatus {
  if (!status) return 'draft'
  return DEAL_STATUS_MAP[status.toUpperCase()] ?? 'draft'
}

export function mapPaymentStatus(status: CoreApiPaymentStatus | string | null | undefined): UiPaymentStatus {
  if (!status) return 'created'
  return PAYMENT_STATUS_MAP[status.toUpperCase()] ?? 'created'
}

export function mapPaymentType(type: CoreApiPaymentType | string | null | undefined): UiPaymentType {
  if (!type) return 'deposit'
  return PAYMENT_TYPE_MAP[type.toUpperCase()] ?? 'deposit'
}

export function mapReminderStatus(status: CoreApiReminderStatus | string | null | undefined): UiReminderStatus {
  if (!status) return 'scheduled'
  return REMINDER_STATUS_MAP[status.toUpperCase()] ?? 'scheduled'
}
