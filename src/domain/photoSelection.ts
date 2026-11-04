export const photoSpecifications = {
  PHOTO_3IN: { label: '3寸照片', box: '89 × 63 mm' },
  PHOTO_4IN: { label: '4寸照片', box: '102 × 76 mm' },
  PHOTO_5IN: { label: '5寸照片', box: '127 × 89 mm' },
  PHOTO_6IN: { label: '6寸照片', box: '152 × 102 mm' },
  PHOTO_6IN_COLLAGE: { label: '6寸照片拼版', box: '1–9张拼成一张六寸相纸' }
} as const
export type PhotoSpecification = keyof typeof photoSpecifications
const KEY = 'miniapp-photo-selection-v1'
export function choosePhotoSpecification(specification: PhotoSpecification) {
  uni.setStorageSync(KEY, { specification, createdAtMs: Date.now() })
}
export function takePhotoSpecification(): PhotoSpecification | null {
  const value = uni.getStorageSync(KEY)
  uni.removeStorageSync(KEY)
  if (!value || !Object.prototype.hasOwnProperty.call(photoSpecifications, value.specification)
    || !Number.isSafeInteger(value.createdAtMs) || Date.now() < value.createdAtMs
    || Date.now() - value.createdAtMs > 5 * 60 * 1000) return null
  return value.specification
}
