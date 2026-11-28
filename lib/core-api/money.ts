/**
 * Converts rupees to paise using integer rounding.
 * e.g. 500 rupees -> 50000 paise.
 */
export function rupeesToPaise(rupees: number): number {
  if (typeof rupees !== 'number' || isNaN(rupees) || rupees <= 0) {
    return 0
  }
  return Math.round(rupees * 100)
}

/**
 * Converts paise to rupees.
 * e.g. 50000 paise -> 500 rupees.
 */
export function paiseToRupees(paise: number): number {
  if (typeof paise !== 'number' || isNaN(paise) || paise <= 0) {
    return 0
  }
  return paise / 100
}
