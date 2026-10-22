/** Source module/group/name mapping supplied by the main conversation.
 * Inventory only, not backend permissions or completed business pages.
 */
export type PrintPageStatus = 'visual-sample' | 'not-implemented'
export interface PrintMenuGroup { module: string; title: string }
export interface PrintMenuEntry {
  sourceId: string
  taskId: string
  title: string
  module: string
  group: string
  status: PrintPageStatus
  route: string | null
  sourceEvidence: string
}
export const printMenuModules: readonly string[] = [
  "工作台",
  "门店",
  "订单",
  "财务",
  "终端",
  "会员",
  "广告"
]
export const printMenuGroups: readonly PrintMenuGroup[] = [
  {
    "module": "工作台",
    "title": "概览"
  },
  {
    "module": "门店",
    "title": "门店管理"
  },
  {
    "module": "门店",
    "title": "价格管理"
  },
  {
    "module": "门店",
    "title": "装订管理"
  },
  {
    "module": "门店",
    "title": "特殊文件计价"
  },
  {
    "module": "门店",
    "title": "文库管理"
  },
  {
    "module": "门店",
    "title": "营销中心"
  },
  {
    "module": "订单",
    "title": "电脑自助打印"
  },
  {
    "module": "订单",
    "title": "手机自助打印"
  },
  {
    "module": "财务",
    "title": "财务管理"
  },
  {
    "module": "财务",
    "title": "分账管理"
  },
  {
    "module": "终端",
    "title": "终端管理"
  },
  {
    "module": "终端",
    "title": "终端设置"
  },
  {
    "module": "终端",
    "title": "打印订单量统计"
  },
  {
    "module": "终端",
    "title": "打印纸张量统计"
  },
  {
    "module": "会员",
    "title": "用户管理"
  },
  {
    "module": "会员",
    "title": "会员卡管理"
  },
  {
    "module": "广告",
    "title": "广告中心"
  }
]
export const printMenuEntries: readonly PrintMenuEntry[] = [
  {
    "sourceId": "P01",
    "taskId": "UI-P01",
    "title": "工作台",
    "module": "工作台",
    "group": "概览",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P02",
    "taskId": "UI-P02",
    "title": "门店详情",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P03",
    "taskId": "UI-P03",
    "title": "门店设置",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P04",
    "taskId": "UI-P04",
    "title": "密码管理",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P05",
    "taskId": "UI-P05",
    "title": "员工管理",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P06",
    "taskId": "UI-P06",
    "title": "远程预约设置",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P07",
    "taskId": "UI-P07",
    "title": "远程配送设置",
    "module": "门店",
    "group": "门店管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P08",
    "taskId": "UI-P08",
    "title": "我的商品",
    "module": "门店",
    "group": "价格管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P09",
    "taskId": "UI-P09",
    "title": "平台商品库",
    "module": "门店",
    "group": "价格管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P10",
    "taskId": "UI-P10",
    "title": "装订设置",
    "module": "门店",
    "group": "装订管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P11",
    "taskId": "UI-P11",
    "title": "图片、准考证等",
    "module": "门店",
    "group": "特殊文件计价",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P12",
    "taskId": "UI-P12",
    "title": "上传文件",
    "module": "门店",
    "group": "文库管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P13",
    "taskId": "UI-P13",
    "title": "文库列表",
    "module": "门店",
    "group": "文库管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P14",
    "taskId": "UI-P14",
    "title": "文库设置",
    "module": "门店",
    "group": "文库管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P15",
    "taskId": "UI-P15",
    "title": "自定义分类",
    "module": "门店",
    "group": "文库管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P16",
    "taskId": "UI-P16",
    "title": "最低消费",
    "module": "门店",
    "group": "营销中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P17",
    "taskId": "UI-P17",
    "title": "满额优惠",
    "module": "门店",
    "group": "营销中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P18",
    "taskId": "UI-P18",
    "title": "微信代金红包",
    "module": "门店",
    "group": "营销中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P19",
    "taskId": "UI-P19",
    "title": "免费打印",
    "module": "门店",
    "group": "营销中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P20",
    "taskId": "UI-P20",
    "title": "关注公众号",
    "module": "门店",
    "group": "营销中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P21",
    "taskId": "UI-P21",
    "title": "用户打印列表",
    "module": "订单",
    "group": "电脑自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P22",
    "taskId": "UI-P22",
    "title": "管理员打印列表",
    "module": "订单",
    "group": "电脑自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P23",
    "taskId": "UI-P23",
    "title": "全部订单列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P24",
    "taskId": "UI-P24",
    "title": "现场打印自取列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P25",
    "taskId": "UI-P25",
    "title": "远程打印自取列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P26",
    "taskId": "UI-P26",
    "title": "定时配送列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P27",
    "taskId": "UI-P27",
    "title": "VIP配送列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P28",
    "taskId": "UI-P28",
    "title": "保存证件照图片列表",
    "module": "订单",
    "group": "手机自助打印",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P29",
    "taskId": "UI-P29",
    "title": "提现管理",
    "module": "财务",
    "group": "财务管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P30",
    "taskId": "UI-P30",
    "title": "交易订单",
    "module": "财务",
    "group": "财务管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P31",
    "taskId": "UI-P31",
    "title": "自动提现设置",
    "module": "财务",
    "group": "财务管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P32",
    "taskId": "UI-P32",
    "title": "分账列表",
    "module": "财务",
    "group": "分账管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P33",
    "taskId": "UI-P33",
    "title": "打印终端",
    "module": "终端",
    "group": "终端管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P34",
    "taskId": "UI-P34",
    "title": "打印机",
    "module": "终端",
    "group": "终端管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P35",
    "taskId": "UI-P35",
    "title": "邮箱发送设置",
    "module": "终端",
    "group": "终端设置",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P36",
    "taskId": "UI-P36",
    "title": "按终端统计",
    "module": "终端",
    "group": "打印订单量统计",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P37",
    "taskId": "UI-P37",
    "title": "按总量统计",
    "module": "终端",
    "group": "打印纸张量统计",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P38",
    "taskId": "UI-P38",
    "title": "按日统计",
    "module": "终端",
    "group": "打印纸张量统计",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P39",
    "taskId": "UI-P39",
    "title": "用户列表",
    "module": "会员",
    "group": "用户管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P40",
    "taskId": "UI-P40",
    "title": "黑白名单用户",
    "module": "会员",
    "group": "用户管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P41",
    "taskId": "UI-P41",
    "title": "会员等级",
    "module": "会员",
    "group": "会员卡管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P42",
    "taskId": "UI-P42",
    "title": "充值赠送",
    "module": "会员",
    "group": "会员卡管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P43",
    "taskId": "UI-P43",
    "title": "充值享折扣",
    "module": "会员",
    "group": "会员卡管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P44",
    "taskId": "UI-P44",
    "title": "会员卡管理",
    "module": "会员",
    "group": "会员卡管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P45",
    "taskId": "UI-P45",
    "title": "打印券管理",
    "module": "会员",
    "group": "会员卡管理",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P46",
    "taskId": "UI-P46",
    "title": "电脑终端广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P47",
    "taskId": "UI-P47",
    "title": "触屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "status": "visual-sample",
    "route": "/print-preview/touch-ad",
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P48",
    "taskId": "UI-P48",
    "title": "全屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P49",
    "taskId": "UI-P49",
    "title": "竖屏终端广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P50",
    "taskId": "UI-P50",
    "title": "云盒广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P51",
    "taskId": "UI-P51",
    "title": "订单页广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P52",
    "taskId": "UI-P52",
    "title": "电脑桌面广告",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  },
  {
    "sourceId": "P53",
    "taskId": "UI-P53",
    "title": "小程序分享图",
    "module": "广告",
    "group": "广告中心",
    "status": "not-implemented",
    "route": null,
    "sourceEvidence": "主对话从原始页面与表单清单逐项提取映射；原始完整JSON尚未同步"
  }
]
export const printPreviewSummary = {
  sourcePages: printMenuEntries.length,
  visualSamples: printMenuEntries.filter((entry) => entry.status === 'visual-sample').length,
  businessPagesAccepted: 0
} as const
