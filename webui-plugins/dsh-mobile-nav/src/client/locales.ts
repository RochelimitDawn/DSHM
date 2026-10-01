/** `mobileNav` namespace dictionaries: drawer controls. */
export const NS = 'mobileNav'

/** Simplified Chinese dictionary (the key-set source of truth). */
export const zh = {
  'open': '打开目录',
  'close': '收起目录',
  'backdrop': '点击关闭目录',
  'sessionLog': '导出会话日志',
  'files': '文件浏览',
  'previewFullscreen': '全屏预览',
  'previewExitFullscreen': '退出全屏',
} as const

/** English dictionary, key-identical to the Chinese source of truth. */
export const en: Record<MobileNavKey, string> = {
  'open': 'Open directory',
  'close': 'Close directory',
  'backdrop': 'Click to close directory',
  'sessionLog': 'Session log',
  'files': 'Files',
  'previewFullscreen': 'Fullscreen preview',
  'previewExitFullscreen': 'Exit fullscreen',
}

/** Key domain of the `mobileNav` namespace (zh is the source of truth). */
export type MobileNavKey = keyof typeof zh
