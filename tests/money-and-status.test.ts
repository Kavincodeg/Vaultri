import { describe, it, expect } from 'vitest'
import { rupeesToPaise, paiseToRupees } from '@/lib/core-api/money'
import {
  mapDealStatus,
  mapPaymentStatus,
  mapPaymentType,
  mapReminderStatus,
} from '@/lib/core-api/status-mapper'

describe('Money Conversion Utility', () => {
  it('converts rupees to paise correctly (e.g. 500 rupees -> 50000 paise)', () => {
    expect(rupeesToPaise(500)).toBe(50000)
    expect(rupeesToPaise(0)).toBe(0)
    expect(rupeesToPaise(12.5)).toBe(1250)
    expect(rupeesToPaise(99.99)).toBe(9999)
  })

  it('converts paise to rupees correctly (e.g. 50000 paise -> 500 rupees)', () => {
    expect(paiseToRupees(50000)).toBe(500)
    expect(paiseToRupees(0)).toBe(0)
    expect(paiseToRupees(1250)).toBe(12.5)
    expect(paiseToRupees(9999)).toBe(99.99)
  })

  it('handles negative or invalid numbers safely', () => {
    expect(rupeesToPaise(NaN)).toBe(0)
    expect(paiseToRupees(NaN)).toBe(0)
    expect(rupeesToPaise(-10)).toBe(0)
    expect(paiseToRupees(-1000)).toBe(0)
  })
})

describe('Core-API Status Mapper', () => {
  describe('Deal Statuses', () => {
    it('maps every core-api deal status to UI deal status', () => {
      expect(mapDealStatus('DRAFT')).toBe('draft')
      expect(mapDealStatus('DEPOSIT_PENDING')).toBe('deposit_pending')
      expect(mapDealStatus('DEPOSIT_PAID')).toBe('deposit_paid')
      expect(mapDealStatus('IN_PROGRESS')).toBe('in_progress')
      expect(mapDealStatus('COMPLETED')).toBe('completed')
      expect(mapDealStatus('CANCELLED')).toBe('cancelled')
    })

    it('falls back gracefully to lowercase or default for unknown deal status', () => {
      expect(mapDealStatus('UNKNOWN_STATUS' as any)).toBe('draft')
      expect(mapDealStatus(undefined as any)).toBe('draft')
      expect(mapDealStatus(null as any)).toBe('draft')
    })
  })

  describe('Payment Statuses', () => {
    it('maps every core-api payment status to UI payment status', () => {
      expect(mapPaymentStatus('PENDING')).toBe('created')
      expect(mapPaymentStatus('PAID')).toBe('completed')
      expect(mapPaymentStatus('CANCELLED')).toBe('cancelled')
      expect(mapPaymentStatus('EXPIRED')).toBe('expired')
      expect(mapPaymentStatus('FAILED')).toBe('failed')
    })

    it('falls back gracefully to created for unknown payment status', () => {
      expect(mapPaymentStatus('UNKNOWN' as any)).toBe('created')
      expect(mapPaymentStatus(undefined as any)).toBe('created')
    })
  })

  describe('Payment Types', () => {
    it('maps core-api payment types to UI payment types', () => {
      expect(mapPaymentType('DEPOSIT')).toBe('deposit')
      expect(mapPaymentType('CANCELLATION_FEE')).toBe('cancellation_fee')
    })

    it('falls back to deposit for unknown type', () => {
      expect(mapPaymentType('UNKNOWN' as any)).toBe('deposit')
    })
  })

  describe('Reminder Statuses', () => {
    it('maps every core-api reminder status to UI reminder status', () => {
      expect(mapReminderStatus('PENDING')).toBe('scheduled')
      expect(mapReminderStatus('SENT')).toBe('sent')
      expect(mapReminderStatus('FAILED')).toBe('failed')
      expect(mapReminderStatus('CANCELLED')).toBe('cancelled')
    })

    it('falls back gracefully to scheduled for unknown reminder status', () => {
      expect(mapReminderStatus('UNKNOWN' as any)).toBe('scheduled')
      expect(mapReminderStatus(undefined as any)).toBe('scheduled')
    })
  })
})
