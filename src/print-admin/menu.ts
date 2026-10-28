/** Original 53-entry inventory. Availability is separate from authorization and acceptance. */
export interface PrintAdminMenuEntry {
  sourceId: string
  title: string
  module: string
  group: string
  route: string | null
}

export const printAdminModules = ['工作台', '门店', '订单', '财务', '终端', '会员', '广告'] as const

export const printAdminMenu: readonly PrintAdminMenuEntry[] = [
  {
    "sourceId": "P01",
    "title": "工作台",
    "module": "工作台",
    "group": "概览",
    "route": "/print/workbench"
  },
  {
    "sourceId": "P02",
    "title": "门店详情",
    "module": "门店",
    "group": "门店管理",
    "route": "/print/store/profile"
  },
  {
    "sourceId": "P03",
    "title": "门店设置",
    "module": "门店",
    "group": "门店管理",
    "route": null
  },
  {
    "sourceId": "P04",
    "title": "密码管理",
    "module": "门店",
    "group": "门店管理",
    "route": "/print/store/password"
  },
  {
    "sourceId": "P05",
    "title": "员工管理",
    "module": "门店",
    "group": "门店管理",
    "route": null
  },
  {
    "sourceId": "P06",
    "title": "远程预约设置",
    "module": "门店",
    "group": "门店管理",
    "route": null
  },
  {
    "sourceId": "P07",
    "title": "远程配送设置",
    "module": "门店",
    "group": "门店管理",
    "route": null
  },
  {
    "sourceId": "P08",
    "title": "我的商品",
    "module": "门店",
    "group": "价格管理",
    "route": "/print/products/mine"
  },
  {
    "sourceId": "P09",
    "title": "平台商品库",
    "module": "门店",
    "group": "价格管理",
    "route": "/print/products/platform"
  },
  {
    "sourceId": "P10",
    "title": "装订设置",
    "module": "门店",
    "group": "装订管理",
    "route": null
  },
  {
    "sourceId": "P11",
    "title": "图片、准考证等",
    "module": "门店",
    "group": "特殊文件计价",
    "route": null
  },
  {
    "sourceId": "P12",
    "title": "上传文件",
    "module": "门店",
    "group": "文库管理",
    "route": null
  },
  {
    "sourceId": "P13",
    "title": "文库列表",
    "module": "门店",
    "group": "文库管理",
    "route": "/print/library/entries"
  },
  {
    "sourceId": "P14",
    "title": "文库设置",
    "module": "门店",
    "group": "文库管理",
    "route": "/print/library/settings"
  },
  {
    "sourceId": "P15",
    "title": "自定义分类",
    "module": "门店",
    "group": "文库管理",
    "route": "/print/library/categories"
  },
  {
    "sourceId": "P16",
    "title": "最低消费",
    "module": "门店",
    "group": "营销中心",
    "route": null
  },
  {
    "sourceId": "P17",
    "title": "满额优惠",
    "module": "门店",
    "group": "营销中心",
    "route": null
  },
  {
    "sourceId": "P18",
    "title": "微信代金红包",
    "module": "门店",
    "group": "营销中心",
    "route": null
  },
  {
    "sourceId": "P19",
    "title": "免费打印",
    "module": "门店",
    "group": "营销中心",
    "route": null
  },
  {
    "sourceId": "P20",
    "title": "关注公众号",
    "module": "门店",
    "group": "营销中心",
    "route": null
  },
  {
    "sourceId": "P21",
    "title": "用户打印列表",
    "module": "订单",
    "group": "电脑自助打印",
    "route": null
  },
  {
    "sourceId": "P22",
    "title": "管理员打印列表",
    "module": "订单",
    "group": "电脑自助打印",
    "route": "/print/admin-print-list"
  },
  {
    "sourceId": "P23",
    "title": "全部订单列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P24",
    "title": "现场打印自取列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P25",
    "title": "远程打印自取列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P26",
    "title": "定时配送列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P27",
    "title": "VIP配送列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P28",
    "title": "保存证件照图片列表",
    "module": "订单",
    "group": "手机自助打印",
    "route": null
  },
  {
    "sourceId": "P29",
    "title": "提现管理",
    "module": "财务",
    "group": "财务管理",
    "route": null
  },
  {
    "sourceId": "P30",
    "title": "交易订单",
    "module": "财务",
    "group": "财务管理",
    "route": null
  },
  {
    "sourceId": "P31",
    "title": "自动提现设置",
    "module": "财务",
    "group": "财务管理",
    "route": null
  },
  {
    "sourceId": "P32",
    "title": "分账列表",
    "module": "财务",
    "group": "分账管理",
    "route": null
  },
  {
    "sourceId": "P33",
    "title": "打印终端",
    "module": "终端",
    "group": "终端管理",
    "route": "/print/terminals"
  },
  {
    "sourceId": "P34",
    "title": "打印机",
    "module": "终端",
    "group": "终端管理",
    "route": "/print/printers"
  },
  {
    "sourceId": "P35",
    "title": "邮箱发送设置",
    "module": "终端",
    "group": "终端设置",
    "route": null
  },
  {
    "sourceId": "P36",
    "title": "按终端统计",
    "module": "终端",
    "group": "打印订单量统计",
    "route": "/print/statistics/terminal"
  },
  {
    "sourceId": "P37",
    "title": "按总量统计",
    "module": "终端",
    "group": "打印纸张量统计",
    "route": "/print/statistics/paper-total"
  },
  {
    "sourceId": "P38",
    "title": "按日统计",
    "module": "终端",
    "group": "打印纸张量统计",
    "route": "/print/statistics/paper-daily"
  },
  {
    "sourceId": "P39",
    "title": "用户列表",
    "module": "会员",
    "group": "用户管理",
    "route": null
  },
  {
    "sourceId": "P40",
    "title": "黑白名单用户",
    "module": "会员",
    "group": "用户管理",
    "route": null
  },
  {
    "sourceId": "P41",
    "title": "会员等级",
    "module": "会员",
    "group": "会员卡管理",
    "route": null
  },
  {
    "sourceId": "P42",
    "title": "充值赠送",
    "module": "会员",
    "group": "会员卡管理",
    "route": null
  },
  {
    "sourceId": "P43",
    "title": "充值享折扣",
    "module": "会员",
    "group": "会员卡管理",
    "route": null
  },
  {
    "sourceId": "P44",
    "title": "会员卡管理",
    "module": "会员",
    "group": "会员卡管理",
    "route": null
  },
  {
    "sourceId": "P45",
    "title": "打印券管理",
    "module": "会员",
    "group": "会员卡管理",
    "route": null
  },
  {
    "sourceId": "P46",
    "title": "电脑终端广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/computer-terminal"
  },
  {
    "sourceId": "P47",
    "title": "触屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/touch-terminal"
  },
  {
    "sourceId": "P48",
    "title": "全屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/fullscreen-terminal"
  },
  {
    "sourceId": "P49",
    "title": "竖屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/portrait-terminal"
  },
  {
    "sourceId": "P50",
    "title": "云盒广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/cloud-box"
  },
  {
    "sourceId": "P51",
    "title": "订单页广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/order-page"
  },
  {
    "sourceId": "P52",
    "title": "电脑桌面广告",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/computer-desktop"
  },
  {
    "sourceId": "P53",
    "title": "小程序分享图",
    "module": "广告",
    "group": "广告中心",
    "route": "/print/advertising/mini-share"
  }
]

export const printAdminGroups = [...new Map(printAdminMenu.map((entry) => [
  `${entry.module}/${entry.group}`, { module: entry.module, title: entry.group }
])).values()]
