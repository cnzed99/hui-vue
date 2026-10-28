export const adPositions = [
  { key: 'COMPUTER_TERMINAL', sourceId: 'P46', title: '电脑终端广告', path: 'computer-terminal', max: 5, size: '274×540像素' },
  { key: 'TOUCH_TERMINAL', sourceId: 'P47', title: '触屏终端广告', path: 'touch-terminal', max: 5, size: '720×240像素' },
  { key: 'FULLSCREEN_TERMINAL', sourceId: 'P48', title: '全屏终端广告', path: 'fullscreen-terminal', max: 5, size: '755×585像素' },
  { key: 'PORTRAIT_TERMINAL', sourceId: 'P49', title: '竖屏终端广告', path: 'portrait-terminal', max: 5, size: '1080×1241像素' },
  { key: 'CLOUD_BOX', sourceId: 'P50', title: '云盒广告', path: 'cloud-box', max: 5, size: '1920×1080像素' },
  { key: 'ORDER_PAGE', sourceId: 'P51', title: '订单页广告', path: 'order-page', max: 2, size: '正反面各一张；宽高待确认，图片大小不超过1M' },
  { key: 'COMPUTER_DESKTOP', sourceId: 'P52', title: '电脑桌面广告', path: 'computer-desktop', max: 1, size: '1920×1080像素' },
  { key: 'MINI_SHARE', sourceId: 'P53', title: '小程序分享图', path: 'mini-share', max: 1, size: '627×486像素' }
] as const
export type AdPosition = typeof adPositions[number]['key']
export const findPosition = (key: string) => adPositions.find((p) => p.key === key)
export const slotsFor = (key: AdPosition) => key === 'ORDER_PAGE' ? ['FRONT', 'BACK'] : Array.from({ length: findPosition(key)!.max }, (_, i) => `IMAGE_${i + 1}`)
export const slotLabel = (slot: string) => slot === 'FRONT' ? '正面广告图' : slot === 'BACK' ? '反面广告图' : `广告图片${slot.substring(6)}`
