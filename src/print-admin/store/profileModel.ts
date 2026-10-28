/** P02 transport contract. Version is decimal text; never convert it to a JS number. */
export interface StoreProfileData {
  storeName: string
  notice: string | null
  provinceCode: string | null
  provinceName: string | null
  cityCode: string | null
  cityName: string | null
  districtCode: string | null
  districtName: string | null
  streetCode: string | null
  streetName: string | null
  address: string | null
  contact: string | null
  telephone: string | null
  qq: string | null
  openTime: string | null
  closeTime: string | null
  businessOpen: boolean | null
}
export interface StoreProfileResponse {
  profileVersion: string
  configured: boolean
  profile: StoreProfileData
  regionDictionaryStatus: 'NOT_CONFIGURED'
  officialAccountQrStatus: 'NOT_CONFIGURED'
  officialAccountQrUrl: null
  miniProgramQrStatus: 'NOT_CONFIGURED'
  miniProgramQrUrl: null
}
export interface ProfileForm {
  storeName: string; notice: string; province: string; city: string; district: string; street: string
  address: string; contact: string; telephone: string; qq: string
  openHour: string; openMinute: string; closeHour: string; closeMinute: string
  businessOpen: boolean | null
}
export const emptyProfileForm = (): ProfileForm => ({ storeName: '', notice: '', province: '', city: '',
  district: '', street: '', address: '', contact: '', telephone: '', qq: '', openHour: '', openMinute: '',
  closeHour: '', closeMinute: '', businessOpen: null })
const timePattern = /^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/
export const parseStoreProfile = (value: unknown): StoreProfileResponse => {
  if (!value || typeof value !== 'object') throw new Error('invalid_profile_response')
  const r = value as StoreProfileResponse
  if (typeof r.profileVersion !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(r.profileVersion) ||
      BigInt(r.profileVersion) > 9223372036854775807n || typeof r.configured !== 'boolean' || !r.profile ||
      typeof r.profile.storeName !== 'string' || !r.profile.storeName.trim() ||
      r.regionDictionaryStatus !== 'NOT_CONFIGURED' || r.officialAccountQrStatus !== 'NOT_CONFIGURED' ||
      r.miniProgramQrStatus !== 'NOT_CONFIGURED' || r.officialAccountQrUrl !== null || r.miniProgramQrUrl !== null) {
    throw new Error('invalid_profile_response')
  }
  for (const field of ['notice', 'provinceCode', 'provinceName', 'cityCode', 'cityName', 'districtCode',
    'districtName', 'streetCode', 'streetName', 'address', 'contact', 'telephone', 'qq', 'openTime', 'closeTime'] as const) {
    if (r.profile[field] !== null && typeof r.profile[field] !== 'string') throw new Error('invalid_profile_response')
  }
  const d = r.profile
  if ((d.businessOpen !== null && typeof d.businessOpen !== 'boolean') ||
      (d.openTime === null) !== (d.closeTime === null) ||
      (d.openTime !== null && (!timePattern.test(d.openTime) || !timePattern.test(d.closeTime!)))) {
    throw new Error('invalid_profile_response')
  }
  return r
}
export const toProfileForm = (data: StoreProfileData): ProfileForm => ({
  storeName: data.storeName, notice: data.notice ?? '', province: data.provinceName ?? '',
  city: data.cityName ?? '', district: data.districtName ?? '', street: data.streetName ?? '',
  address: data.address ?? '', contact: data.contact ?? '', telephone: data.telephone ?? '', qq: data.qq ?? '',
  openHour: data.openTime?.slice(0, 2) ?? '', openMinute: data.openTime?.slice(3) ?? '',
  closeHour: data.closeTime?.slice(0, 2) ?? '', closeMinute: data.closeTime?.slice(3) ?? '', businessOpen: data.businessOpen
})
export const toStoreProfileCommand = (form: ProfileForm, loaded: StoreProfileResponse): StoreProfileData & { expectedVersion: string } => {
  const fields = [form.openHour, form.openMinute, form.closeHour, form.closeMinute]
  if (fields.some(Boolean) && (!fields.every(Boolean) || !timePattern.test(`${form.openHour}:${form.openMinute}`) ||
      !timePattern.test(`${form.closeHour}:${form.closeMinute}`))) throw new Error('请完整选择有效的开始和结束时间，或清空营业时间。')
  const nullable = (text: string) => text === '' ? null : text
  return { expectedVersion: loaded.profileVersion, storeName: form.storeName, notice: nullable(form.notice),
    provinceCode: loaded.profile.provinceCode, provinceName: loaded.profile.provinceName,
    cityCode: loaded.profile.cityCode, cityName: loaded.profile.cityName,
    districtCode: loaded.profile.districtCode, districtName: loaded.profile.districtName,
    streetCode: loaded.profile.streetCode, streetName: loaded.profile.streetName,
    address: nullable(form.address), contact: nullable(form.contact), telephone: nullable(form.telephone), qq: nullable(form.qq),
    openTime: fields.every(Boolean) ? `${form.openHour}:${form.openMinute}` : null,
    closeTime: fields.every(Boolean) ? `${form.closeHour}:${form.closeMinute}` : null, businessOpen: form.businessOpen }
}
