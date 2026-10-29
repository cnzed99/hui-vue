export interface ReservationSettings {
  version: string
  configured: boolean
  pickupEnabled: boolean
  timedDeliveryEnabled: boolean
  vipDeliveryEnabled: boolean
  pickupCodePrefix: string
  automaticPrintEnabled: boolean
  automaticPrintExecutionStatus: 'SIMULATED_CONFIG_ONLY'
  storeSettingsLinkStatus: 'PENDING_CONFIRMATION'
}
export interface ReservationSettingsCommand {
  expectedVersion: string
  pickupEnabled: boolean
  timedDeliveryEnabled: boolean
  vipDeliveryEnabled: boolean
  pickupCodePrefix: string
  automaticPrintEnabled: boolean
}
export const validatePrefix = (value: string) => /^[A-Za-z0-9]{0,2}$/.test(value) ? '' : '取件码前缀仅支持字母或者数字，限制两个字符以内。'
export const parseReservationSettings = (raw: unknown): ReservationSettings => {
  const d = raw as ReservationSettings
  if (!d || typeof d !== 'object' || typeof d.version !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(d.version) ||
      BigInt(d.version) > 9223372036854775807n || typeof d.configured !== 'boolean' ||
      typeof d.pickupEnabled !== 'boolean' || typeof d.timedDeliveryEnabled !== 'boolean' ||
      typeof d.vipDeliveryEnabled !== 'boolean' || typeof d.automaticPrintEnabled !== 'boolean' ||
      typeof d.pickupCodePrefix !== 'string' || !!validatePrefix(d.pickupCodePrefix) ||
      d.automaticPrintExecutionStatus !== 'SIMULATED_CONFIG_ONLY' || d.storeSettingsLinkStatus !== 'PENDING_CONFIRMATION' ||
      (d.configured ? d.version === '0' : d.version !== '0' || d.pickupEnabled || d.timedDeliveryEnabled ||
        d.vipDeliveryEnabled || d.automaticPrintEnabled || d.pickupCodePrefix !== '')) throw new Error('invalid_reservation_settings_response')
  return d
}
