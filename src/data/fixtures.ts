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

export interface LibraryDocument {
  id: number
  title: string
  category: string
  source: string
  views: number
  prints: number
  pages: number
}

export const libraryDocuments: LibraryDocument[] = [
  { id: 1, title: '2024年12月英语四级真题第1套解析', category: '英语四级', source: '小板栗文件库', views: 310, prints: 55, pages: 18 },
  { id: 2, title: '2025年06月英语四级真题第1套', category: '英语四级', source: '小板栗文件库', views: 125, prints: 38, pages: 8 },
  { id: 3, title: '2024年12月英语六级真题第1套', category: '其他文档', source: '小板栗文件库', views: 89, prints: 29, pages: 9 },
  { id: 4, title: '2025年06月英语六级真题第1套', category: '其他文档', source: '小板栗文件库', views: 86, prints: 26, pages: 9 },
  { id: 5, title: '2025年06月英语四级真题第2套', category: '英语四级', source: '小板栗文件库', views: 34, prints: 16, pages: 8 }
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
