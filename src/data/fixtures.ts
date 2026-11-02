export type NavKey = 'home' | 'library' | 'profile'

export interface PrintEntry {
  label: string
  description?: string
  icon: string
  tone: 'orange' | 'pink' | 'blue' | 'green' | 'cyan'
}

export const commonPrintEntries: PrintEntry[] = [
  { label: '文档打印', description: '聊天文件、云文档等打印', icon: '▤', tone: 'orange' },
  { label: '图片打印', description: '图片、拼版打印在A4等纸上', icon: '▧', tone: 'pink' },
  { label: '照片冲印', description: '5寸、6寸、拼版等照片冲印', icon: '▣', tone: 'pink' },
  { label: '证件照', description: '1寸、2寸、3寸等更多', icon: '♙', tone: 'blue' },
  { label: '扫描复印', description: '身份证、证件、文件等复印', icon: '▥', tone: 'green' },
  { label: '压缩包打印', description: '在线解压打印', icon: '▦', tone: 'cyan' }
]

export const morePrintEntries: PrintEntry[] = [
  { label: '发票打印', icon: '▤', tone: 'blue' },
  { label: '文字速印', icon: 'T', tone: 'green' },
  { label: '链接打印', icon: '↗', tone: 'orange' },
  { label: '试卷翻新', icon: '▰', tone: 'cyan' }
]

export const profileMenus = [
  { label: '我的订单', note: '售后服务与支持', icon: '▤', tone: 'orange' },
  { label: '会员卡', icon: '▣', tone: 'blue' },
  { label: '代金红包', icon: '¥', tone: 'orange' },
  { label: '我的文库', icon: '▧', tone: 'orange' },
  { label: '服务中心', icon: '◎', tone: 'blue' },
  { label: '我要加盟', icon: '盟', tone: 'orange' },
  { label: '隐私协议', icon: '♡', tone: 'green' }
]
