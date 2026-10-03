/**
 * dsh-notification: DSH 会话事件外发桥（DSHM 通知）。
 *
 * 订阅 session/event：
 * - `assistant/message`：暂存每会话最后一条回复摘要（代码围栏替换为占位、
 *   markdown 轻清理、空白压缩——通知卡里代码不可读，保持卡面干净）；
 * - `turn/end`：把语义化事件 JSON 追加写入 $DSH_HOME/.siliconleap-events/，
 *   DSHM 壳侧经 FileObserver 消费后删除并推系统通知。
 *
 * 噪音边界：跳过子代理会话（origin=subagent，主 agent 仍在跑）与 aborted
 * 回合（用户主动取消，无需通知）。
 *
 * 文件协议与 DSHM 的 umarm 命令通道一致：hostfs 对事件的可见写入点可靠，
 * guest 内零网络外发。全部路径 try 防御——本插件任何失败都不影响 agent 主流程。
 */
import fs from 'node:fs'
import path from 'node:path'

export const name = 'dsh-notification'

const EVENTS_DIR_NAME = '.siliconleap-events'
const EXCERPT_MAX = 300

export function apply(ctx) {
  const eventsDir = resolveEventsDir()
  try {
    fs.mkdirSync(eventsDir, { recursive: true })
  } catch {
    // 目录不可用则本插件空转，不订阅事件
    return
  }

  const lastExcerpt = new Map()

  ctx.on('session/event', (session, event) => {
    try {
      if (!event || typeof event.type !== 'string') return
      const sid = sessionIdOf(session)
      if (event.type === 'assistant/message') {
        lastExcerpt.set(sid, excerptOf(event.data && event.data.message))
        return
      }
      if (event.type !== 'turn/end') return
      const reason = event.data && event.data.reason
      const kind = (reason && reason.kind) || 'completed'
      // 用户主动取消：自己知道，无需通知
      if (kind === 'aborted') {
        lastExcerpt.delete(sid)
        return
      }
      // 子代理会话：主 agent 仍在跑，回合结束对用户是噪音
      const header = session && session.header
      if (header && header.origin === 'subagent') {
        lastExcerpt.delete(sid)
        return
      }
      writeEvent(eventsDir, {
        type: 'turn_end',
        sessionId: sid,
        title: titleOf(session),
        excerpt: lastExcerpt.get(sid) || '',
        reason: kind,
        error: kind === 'failed' ? errorMessage(reason) : '',
        time: Date.now(),
      })
      lastExcerpt.delete(sid)
    } catch {
      // 外发失败静默：通知是尽力而为的旁路
    }
  })
}

/** $DSH_HOME/.siliconleap-events/；DSHM 壳侧监听该目录。 */
function resolveEventsDir() {
  const home = process.env.DSH_HOME || path.join(process.env.HOME || '', '.dsh')
  return path.join(home, EVENTS_DIR_NAME)
}

/** 追加写语义化事件文件（原子命名，壳侧消费后删除）。 */
function writeEvent(eventsDir, payload) {
  const name = `${Date.now()}-${Math.random().toString(36).slice(2, 8)}.json`
  fs.writeFileSync(path.join(eventsDir, name), JSON.stringify(payload))
}

function sessionIdOf(session) {
  const v = session && (session.id ?? (session.header && session.header.id))
  return typeof v === 'string' ? v : ''
}

/** 会话展示标题：cwd 末段 + id 短形式（SessionHeader 无 title 字段）。 */
function titleOf(session) {
  const cwd = session && session.header && session.header.cwd
  const base = typeof cwd === 'string' && cwd.length > 0 ? path.basename(cwd) : ''
  const sid = sessionIdOf(session)
  const short = sid.length > 8 ? sid.slice(0, 8) : sid
  return [base, short].filter(Boolean).join(' · ') || short
}

/**
 * 回复摘要：content 为字符串直取；块数组拼接文本段（仅文本块，忽略图片等）。
 * 代码围栏整体替换为「（代码 N 行）」占位，行内代码/标题/加粗/链接做 markdown
 * 轻清理，空白压缩为单空格——代码在通知卡里不可读，纯文本保持卡面干净。
 * 清理后过短（纯代码回复）时回退原始文本首行。
 */
function excerptOf(message) {
  if (!message) return ''
  const content = message.content
  let raw = ''
  if (typeof content === 'string') {
    raw = content
  } else if (Array.isArray(content)) {
    raw = content
      .map((b) => (typeof b === 'string' ? b : b && typeof b.text === 'string' ? b.text : ''))
      .filter(Boolean)
      .join('\n')
  } else {
    return ''
  }
  const cleaned = cleanForNotification(raw)
  if (cleaned.length >= 20) return cleaned.slice(0, EXCERPT_MAX)
  // 清理后过短（纯代码/纯表格回复）：回退原始文本首个非空且非围栏行
  const firstLine = raw
    .split('\n')
    .map((l) => l.trim())
    .find((l) => l.length > 0 && !l.startsWith('```')) || ''
  return firstLine.slice(0, EXCERPT_MAX)
}

function cleanForNotification(text) {
  return text
    // 多行代码围栏 → 占位（保留行数信息）
    .replace(/```[\s\S]*?```/g, (m) => `（代码 ${m.split('\n').length - 2} 行）`)
    .replace(/```[\s\S]*/g, '（代码片段）')
    // 行内代码 / 加粗 / 斜体 / 链接 / 标题 轻清理
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1')
    .replace(/^#{1,6}\s+/gm, '')
    .replace(/\s+/g, ' ')
    .trim()
}

/** 失败原因摘要：TurnEndReason.failed 的 error.message / error.code。 */
function errorMessage(reason) {
  const err = reason && reason.data && reason.data.error
  if (!err) return ''
  if (typeof err.message === 'string' && err.message.length > 0) return err.message.slice(0, EXCERPT_MAX)
  if (typeof err.code === 'string') return err.code
  return ''
}
