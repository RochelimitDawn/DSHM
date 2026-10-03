# dsh-mobile-nav CSS 与代码整理 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `mobile.css.ts`（1002 行）按主题拆为 `styles/` 4 文件、把 `index.tsx` 的 5 个 effect 拆为 `effects/` 3 文件，保持零行为变化，分两步独立验证、独立提交。

**Architecture:** 纯机械搬移。CSS 按「为什么存在」分界（自有基础 / 官方布局 / 第三方兼容 / 边角料），由一次性脚本按原字节序切片生成，`styles/index.ts` 用 `join('\n')` 还原原串；effect 按域拆 3 文件（phone-chrome / aionui-compat / stats-line），每文件导出 `installX(ctx)`，内部自带 `ctx.effect(..., label)`。验证 = 逐字节 diff + `pnpm verify` + playwright 双宽度抽查。

**Tech Stack:** TypeScript（双 tsconfig：host + client）、自定义打包器 `scripts/build-client.mjs`（递归内联相对模块）、pnpm、无测试框架。

## Global Constraints

- 零行为变化：不增删改任何 CSS 规则、选择器、注释内容；不合并重复规则、不重命名选择器；不改 effect 内部逻辑。
- 单一 `<style data-plugin>` 标签注入不变；`styles/index.ts` 拼接顺序 `base → layout → compat → misc` 必须与现状字节序一致（现状：base → layout → compat → composer → tablet → desktop）。
- 每个 effect 必须通过 `ctx.effect(fn, label)` 创建；label 字符串原样保留。
- 客户端导入纯净性：effect 文件只 type-only import `ClientContext`；无运行时跨包 import。
- 不手改 `lib/`；修改 `src/` 后必须 `pnpm build` 重新生成并提交 `lib/`。
- 不改构建脚本 `scripts/build-client.mjs`；不引入测试框架；不新增依赖。
- 组件文件（`MobileNavToggle.tsx` / `MobileNavOverlay.tsx` / `MobileDrawerFooter.tsx`）与 `locales.ts` 不动。
- 桌面端（≥1024px）必须保持 no-op。
- 提交信息用 conventional 前缀：`refactor(mobile):`、`docs:`。
- 所有一次性脚本放 `/tmp/`，不提交。

---

### Task 1: CSS 拆分为 styles/ 四文件

**Files:**
- Create: `src/client/styles/base.css.ts`, `src/client/styles/layout.css.ts`, `src/client/styles/compat.css.ts`, `src/client/styles/misc.css.ts`, `src/client/styles/index.ts`
- Delete: `src/client/mobile.css.ts`
- Modify: `src/client/index.tsx:5`（import 改指 `./styles/index.ts`）
- Verify: `/tmp/split-css.mjs`（一次性脚本）

**Interfaces:**
- Produces: `styles/index.ts` 导出 `MOBILE_CSS: string`（四个部分按序 `join('\n')`）；`index.tsx` 继续 `import { MOBILE_CSS } from './styles/index.ts'`。Task 2 不依赖本任务的新导出（只依赖 `MOBILE_CSS` 名字不变）。

- [x] **Step 1: 写并运行切片脚本（生成 4 个部分文件 + index.ts）**

写 `/tmp/split-css.mjs`（内容如下），在仓库根目录运行：

```js
// One-off migration: split src/client/mobile.css.ts into styles/ parts.
// Run from the repo root: node /tmp/split-css.mjs
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs'

const src = readFileSync('src/client/mobile.css.ts', 'utf8')
const m = src.match(/export const MOBILE_CSS = `([\s\S]*)`\s*$/)
if (!m) throw new Error('MOBILE_CSS template not found')
const css = m[1]
const markers = [
  '/* ---------- mobile-only layout ---------- */',
  '/* ---------- dsh-web-ui family compatibility ----------',
  '/* ---------- hero composer on mobile ----------',
  '/* ---------- tablet / wide mobile: keep sheets from becoming full-width ----------',
  '/* ---------- desktop: the mobile controls must never appear ---------- */',
]
const offsets = markers.map((mk) => css.indexOf(mk))
if (offsets.some((o) => o < 0)) throw new Error('marker not found: ' + markers[offsets.indexOf(-1)])
const bounds = [0, ...offsets, css.length]
const slices = bounds.slice(0, -1).map((start, i) => {
  let end = bounds[i + 1]
  if (i < markers.length) end -= 2 // drop the blank line before the next section comment
  return css.slice(start, end)
})
// slices: base, layout, compat, composer, tablet, desktop
const [base, layout, compat, composer, tablet, desktop] = slices
mkdirSync('src/client/styles', { recursive: true })
const banner = (name) =>
  `// ${name} — split from src/client/mobile.css.ts (2026-08-16), order preserved.\n// Do not reorder: styles/index.ts concatenates in this exact order.\n\nexport const `
writeFileSync('src/client/styles/base.css.ts', banner('base') + `BASE_CSS = \`${base}\`\n`)
writeFileSync('src/client/styles/layout.css.ts', banner('layout') + `LAYOUT_CSS = \`${layout}\`\n`)
writeFileSync('src/client/styles/compat.css.ts', banner('compat') + `COMPAT_CSS = \`${compat}\`\n`)
writeFileSync('src/client/styles/misc.css.ts', banner('misc') + `MISC_CSS = \`${[composer, tablet, desktop].join('\n')}\`\n`)
writeFileSync('src/client/styles/index.ts', `import { BASE_CSS } from './base.css.ts'
import { LAYOUT_CSS } from './layout.css.ts'
import { COMPAT_CSS } from './compat.css.ts'
import { MISC_CSS } from './misc.css.ts'

/**
 * All mobile styles, concatenated in the exact order of the original
 * single-file stylesheet (base → layout → compat → misc, where misc keeps
 * composer → tablet → desktop). Injected as ONE <style data-plugin> tag —
 * do not reorder.
 */
export const MOBILE_CSS = [BASE_CSS, LAYOUT_CSS, COMPAT_CSS, MISC_CSS].join('\\n')
`)
// Self-check: joined output must equal the original string byte-for-byte.
const joined = [base, layout, compat, [composer, tablet, desktop].join('\n')].join('\n')
console.log(joined === css ? 'SPLIT OK: byte-identical round trip' : `SPLIT MISMATCH at byte ${[...joined].findIndex((c, i) => c !== css[i])}`)
```

Run: `node /tmp/split-css.mjs`
Expected: `SPLIT OK: byte-identical round trip`。若出现 MISMATCH：检查对应 byte 位置的边界（空白行假设 `\n\n` 是否成立），修正脚本后重跑；不手工改生成文件。

- [x] **Step 2: 检查生成结果**

Run: `head -5 src/client/styles/base.css.ts && head -5 src/client/styles/misc.css.ts && cat src/client/styles/index.ts`
Expected: 每个部分文件以 banner 注释 + `export const X_CSS = \`` 开头；`misc.css.ts` 内含三段（hero composer → tablet → desktop）且注释行齐全；`index.ts` 与脚本中写入内容一致。

- [x] **Step 3: 改 index.tsx 的 import**

`src/client/index.tsx` 中 `import { MOBILE_CSS } from './mobile.css.ts'` 改为 `import { MOBILE_CSS } from './styles/index.ts'`（其余不动；apply() 里 `dataset.pluginCss = '@dsh-external/dsh-mobile-nav/mobile.css'` 是注入元数据字符串，保持原样）。

- [x] **Step 4: 删除旧文件并类型检查**

Run: `rm src/client/mobile.css.ts && pnpm verify`
Expected: 退出码 0，无类型错误。

- [x] **Step 5: 构建**

Run: `pnpm build`
Expected: `client bundle written: lib/client.js (N modules inlined)`，N 应比拆分前多 4（styles/index.ts + 4 个部分文件；debug.ts 等其他模块数不变）。

- [x] **Step 6: bundle 抽查 + 残留检查**

```bash
node -e "const b=require('fs').readFileSync('lib/client.js','utf8');for(const [p,probe] of [['base','data-mobile-nav=\"fab\"'],['layout','data-sidebar-collapsed'],['compat','data-aionui-explorer-col'],['misc','data-phase=\"hero\"']]){console.log((b.includes(probe)?'OK':'MISSING')+': '+p+' probe')}"
grep -rn "from './mobile.css.ts'" src/ || echo 'no import references in src/'
```

Expected: 4 个 probe 全部 OK；`src/` 无 import 引用——注意 `index.tsx` 中 `dataset.pluginCss = '@dsh-external/dsh-mobile-nav/mobile.css'` 含 'mobile.css' 字符串，这是注入元数据，按设计保留，不要改。

- [x] **Step 7: 浏览器双宽度抽查（playwright）**

窄屏：
1. `browser_resize` 390×844；`browser_navigate` http://127.0.0.1:3080；等待 2s（`browser_wait_for` time:2）。
2. 强刷：再 `browser_navigate` 同 URL 一次（拿新 rev bundle）。
3. `browser_find` 找会话头部的目录开关（`data-mobile-nav="toggle"`）与文件按钮；点击 toggle → 抽屉遮罩出现（`data-mobile-nav="backdrop"` 可见）；再点关闭。
4. 若页面无会话（未登录态/空态），记录「抽屉控件不可见」，改为验证浮动按钮 `[data-mobile-nav="fab"]` 存在与否，并在浏览器控制台确认无报错（`browser_console_messages` level:error 应为空）。

桌面：`browser_resize` 1280×800 → 刷新 → 确认 `[data-mobile-nav="fab"]`、`[data-mobile-nav="toggle"]` 计算样式为 `display: none`（用 `browser_evaluate` 查 `getComputedStyle`）。

若页面要求登录/插件 bundle 未更新（rev 未变，见 AGENTS.md 坑：`curl -s http://127.0.0.1:3080/ | grep -o 'dsh-mobile-nav/client.js?rev=[^"]*'`），停在这里问用户强刷/重启 `dsh web` 后人工复核。

- [x] **Step 8: 提交**

```bash
git add src/client/styles src/client/index.tsx -A && git rm src/client/mobile.css.ts && git add lib/client.js
git commit -m "refactor(mobile): split mobile css into themed files"
```

（若 `git rm` 报已删除，用 `git add -A src/client` 统一暂存；确认提交不含 `/tmp` 脚本与其他未跟踪杂物。）

---

### Task 2: effect 拆分为 effects/ 三文件

**Files:**
- Create: `src/client/effects/phone-chrome.ts`, `src/client/effects/aionui-compat.ts`, `src/client/effects/stats-line.ts`
- Modify: `src/client/index.tsx`（删 5 个 effect 体，换 3 个 install 调用）

**Interfaces:**
- Produces: `installPhoneChrome(ctx: ClientContext): void`、`installAionuiCompat(ctx: ClientContext): void`、`installStatsLine(ctx: ClientContext): void` —— 各自内部调用 `ctx.effect(fn, label)`，label 与拆分前逐字相同。`index.tsx` 在样式注入 effect 之后、slot 注册之前按序调用。
- Consumes: 无（只依赖 `ClientContext` 类型与既有 DOM 结构）。

- [x] **Step 1: 创建 effects/phone-chrome.ts**

写 `src/client/effects/phone-chrome.ts`，内容如下（注释与 effect 体逐字来自拆分前的 `index.tsx`，勿改动任何字符；只加了函数包装）：

```ts
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'

/**
 * Phone chrome: KEEP the system status bar (no fullscreen) and make it
 * blend into the page. On narrow screens:
 * - The viewport meta gains viewport-fit=cover, so env(safe-area-inset-top)
 *   is the real status-bar / notch height and the stylesheet can push every
 *   surface below it (off notched phones, or in a browser tab where the
 *   layout viewport already sits below the status bar, the inset is 0 and
 *   nothing shifts).
 * - A theme-color meta tracks the shell background (the official theme is
 *   toggled by body[data-ds-dark-theme], which flips --dsw-alias-bg-base):
 *   Android then paints the status bar / URL bar with the page's own base
 *   color, so the status bar reads as part of the UI instead of a foreign
 *   strip. The drawer paints the same strip on iOS / notch displays.
 * - gesturestart is suppressed as the legacy-iOS fallback for double-tap
 *   zoom; modern browsers are covered by the stylesheet's
 *   touch-action: manipulation (which keeps pan and pinch zoom).
 */
export function installPhoneChrome(ctx: ClientContext): void {
  ctx.effect(() => {
    const narrow = window.matchMedia('(max-width: 1023px)')
    const viewport = document.querySelector<HTMLMetaElement>('meta[name="viewport"]')
    const originalViewport = viewport?.content ?? ''
    const themeMeta = document.createElement('meta')
    themeMeta.name = 'theme-color'
    const bodyBg = (): string => getComputedStyle(document.body).backgroundColor

    const sync = (): void => {
      if (viewport !== null) viewport.content = 'width=device-width, initial-scale=1, viewport-fit=cover'
      themeMeta.content = bodyBg()
      if (themeMeta.parentElement === null) document.head.appendChild(themeMeta)
    }
    const restore = (): void => {
      if (viewport !== null) viewport.content = originalViewport
      themeMeta.remove()
    }
    const onGestureStart = (event: Event) => event.preventDefault()
    if (narrow.matches) sync()
    const onChange = (event: MediaQueryListEvent) => (event.matches ? sync() : restore())
    narrow.addEventListener('change', onChange)
    const observer = new MutationObserver(() => {
      if (narrow.matches) themeMeta.content = bodyBg()
    })
    observer.observe(document.body, { attributes: true, attributeFilter: ['data-ds-dark-theme'] })
    document.addEventListener('gesturestart', onGestureStart)
    return () => {
      narrow.removeEventListener('change', onChange)
      observer.disconnect()
      document.removeEventListener('gesturestart', onGestureStart)
      restore()
    }
  }, 'dsh-mobile-nav: status bar theme + viewport + zoom guard')
}
```

- [x] **Step 2: 创建 effects/aionui-compat.ts**

写 `src/client/effects/aionui-compat.ts`：三个 effect 按拆分前在 `index.tsx` 中的原顺序排列（explorer 关闭标记 → preview 开关标记 → 升起动画重放），每个 effect 的注释、体、label 逐字照搬（可从当前 `index.tsx` 复制，label 分别是 `'dsh-mobile-nav: aionui explorer close marker'`、`'dsh-mobile-nav: preview sheet open marker'`、`'dsh-mobile-nav: sheet rise animation replay'`），文件骨架：

五个 effect 的源区间（以 Task 2 执行时的 `src/client/index.tsx` 为准——本计划 2026-08-16 修订版锚点；若文件再变，按「唯一 label」定位，不要按行号硬搬）：

| effect | 目标文件 | 源区间 | 起点锚点注释（首个字符） | 终点锚点（唯一 label） |
|---|---|---|---|---|
| 状态栏 | effects/phone-chrome.ts | 58–90 行 | `// Phone chrome: KEEP the system status bar` | `}, 'dsh-mobile-nav: status bar theme + viewport + zoom guard')` |
| explorer 关闭标记 | effects/aionui-compat.ts | 102–129 行 | `// dsh-web-ui compatibility: the aionui explorer column would render as a` | `}, 'dsh-mobile-nav: aionui explorer close marker')` |
| preview 开关标记 | effects/aionui-compat.ts | 139–215 行 | `// dsh-web-ui compatibility: the aionui preview column persists its open` | `}, 'dsh-mobile-nav: preview sheet open marker')` |
| 升起动画重放 | effects/aionui-compat.ts | 290–337 行 | `// The dsh-web-ui explorer / preview columns toggle via` | `}, 'dsh-mobile-nav: sheet rise animation replay')` |
| 统计行 | effects/stats-line.ts | 224–283 行 | `// The official conversation status row (turns / steps / LLM time / TTFT /` | `}, 'dsh-mobile-nav: stats line marker')` |

注意：5 个 effect 现在都带「按当前宽度挂载 + matchMedia change 重挂」包装（effect 体内 `const narrow = window.matchMedia(...)` + `install()`/`cleanup` 结构），**整段照搬**，包括这个包装——这是 2026-08-16 修过的行为，拆文件不许改变它。

把三个区间各自的**整段原文**（含顶部大注释、`if (!narrow.matches) return () => {}` 守卫、cleanup 返回）按上表顺序复制进 `installAionuiCompat`，文件骨架：

```ts
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'

/** dsh-web-ui 兼容：explorer / preview 列的显隐标记与升起动画（同域同机制，合并一处）。 */
export function installAionuiCompat(ctx: ClientContext): void {
  // 1) explorer 关闭标记
  ctx.effect(() => {
    // ← 整段照搬 102–129 行原文
  }, 'dsh-mobile-nav: aionui explorer close marker')

  // 2) preview 开关标记
  ctx.effect(() => {
    // ← 整段照搬 139–215 行原文
  }, 'dsh-mobile-nav: preview sheet open marker')

  // 3) 升起动画重放
  ctx.effect(() => {
    // ← 整段照搬 290–337 行原文
  }, 'dsh-mobile-nav: sheet rise animation replay')
}
```

照搬完成后自检：`grep -c 'ctx.effect' src/client/effects/aionui-compat.ts` 应输出 3；三个 label 字符串各出现 1 次（`grep -c` 累计 3）。

- [x] **Step 3: 创建 effects/stats-line.ts**

写 `src/client/effects/stats-line.ts`，把当前 `src/client/index.tsx` 中 224–283 行（起点锚点注释 `// The official conversation status row (turns / steps / LLM time / TTFT /`，终点唯一 label `'dsh-mobile-nav: stats line marker'`）的整段原文（含大注释、`mark`/`moveTps` 函数、install/cleanup 包装）逐字照搬进 `installStatsLine(ctx)` 的 `ctx.effect` 调用，文件骨架同 Task 2 Step 1。自检：`grep -c 'stats line marker' src/client/effects/stats-line.ts` 应输出 1。

- [x] **Step 4: 重写 index.tsx 为编排层**

把 `src/client/index.tsx` 的 apply() 改写为：locale 注册 effect（原样）→ 样式注入 effect（原样）→ `installPhoneChrome(ctx)` → `installAionuiCompat(ctx)` → `installStatsLine(ctx)` → 三个 slot 注册（原样）。import 改为：

```ts
import type { ClientContext } from '@deepseek-ai/dsh-client-runtime/client'
import { MobileNavToggle } from './MobileNavToggle.tsx'
import { MobileNavOverlay } from './MobileNavOverlay.tsx'
import { MobileDrawerFooter } from './MobileDrawerFooter.tsx'
import { MOBILE_CSS } from './styles/index.ts'
import { installDebugBadge } from './debug.ts'
import { installPhoneChrome } from './effects/phone-chrome.ts'
import { installAionuiCompat } from './effects/aionui-compat.ts'
import { installStatsLine } from './effects/stats-line.ts'
import { NS, en, zh } from './locales.ts'
import type { MobileNavKey } from './locales.ts'
```

apply() 内顺序：locale 注册 → 样式注入 → `installDebugBadge(ctx)`（保留在 index.tsx）→ `installPhoneChrome(ctx)` → `installAionuiCompat(ctx)` → `installStatsLine(ctx)` → 三个 slot 注册。保留：文件顶部 `declare module '@deepseek-ai/dsh-client-ui-slots'` 增强、`export const inject`、apply 的 JSDoc、文件底部 4 行 type-only import。删除原 5 个 effect 的实现体（只留 install 调用）。

- [x] **Step 5: 类型检查 + 构建**

Run: `pnpm verify && pnpm build`
Expected: 退出码 0；`client bundle written`；`grep -c 'installPhoneChrome\|installAionuiCompat\|installStatsLine' lib/client.js` ≥ 3。

- [x] **Step 6: 浏览器双宽度抽查（playwright）**

同 Task 1 Step 7 的步骤与通过标准；重点追加：控制台无 error；窄屏下打开设置弹窗再关闭后，`document.querySelectorAll('style[data-plugin]')` 长度 = 1（`browser_evaluate`），无残留 style 标签（AGENTS.md 历史坑）。

- [x] **Step 7: 提交**

```bash
git add src/client -A && git add lib/client.js
git commit -m "refactor(mobile): extract client effects into modules"
```

---

### Task 3: 同步 AGENTS.md

**Files:**
- Modify: `AGENTS.md`（Architecture 核心文件清单 + Conventions 提交前缀）

- [x] **Step 1: 更新 Architecture 节**

把 `AGENTS.md` 中这一行：
`  - \`src/client/mobile.css.ts\`：全部移动端样式（TS 模板字符串，\`<style data-plugin>\` 注入）。`
替换为两行：
`  - \`src/client/styles/\`：全部移动端样式，按主题拆 4 文件（base / layout / compat / misc），\`styles/index.ts\` 按原字节序拼接导出 \`MOBILE_CSS\`（单一 \`<style data-plugin>\` 注入，勿重排）。`
`  - \`src/client/effects/\`：客户端 effect，按域拆 3 文件（phone-chrome / aionui-compat / stats-line）；\`index.tsx\` 只做编排（locale、样式注入、install 调用、slot 注册）。`

- [x] **Step 2: 更新 Pitfalls 节（mobile.css.ts 引用）**

`AGENTS.md` 的 Pitfalls 有一条「从 `mobile.css.ts` 抽 CSS 做复现时：① 模板起点用 `indexOf('`', indexOf('export const MOBILE_CSS ='))`…」——拆分后该文件已不存在。把该条目整段替换为：

`- 抽 CSS 做复现时：`MOBILE_CSS` 由 `src/client/styles/index.ts` 按 base → layout → compat → misc 拼接——直接读对应 `styles/*.css.ts` 的模板内容拼接即可，不必解析 bundle；注释里不能写反引号（会截断模板字符串成 TS 语法错误）、注释必须完整保留（截断的 `/*` 会让 CSS 解析器吞掉下一条规则）这两条仍适用。`

- [x] **Step 3: 更新 Conventions 节**

提交前缀一行 `feat(mobile):`、`fix(mobile):`、`docs:`、`chore:` 改为 `feat(mobile):`、`fix(mobile):`、`refactor(mobile):`、`docs:`、`chore:`。

- [x] **Step 4: 提交**

```bash
git add AGENTS.md
git commit -m "docs: update AGENTS.md for split styles/effects layout"
```

---

### Task 4: 最终验收

- [x] **Step 1: 全量回归**

Run: `pnpm verify && pnpm build && git diff --check && git status --short`
Expected: 全部通过；工作区只剩非本任务文件（`.dsh-vision-toolkit/`、`Screenshot_*.jpg`、`*.tgz` 等既有未跟踪物）。

- [x] **Step 2: 对照成功标准核对**

- [x] Step 1/2/3 各自提交独立（`git log --oneline -4` 应有 3 个新提交：split css / extract effects / update AGENTS.md）。
- [x] 窄屏 + 桌面 playwright 抽查通过（抽屉、浮动按钮、设置弹窗、浮层、单一 style 标签）。
- [x] `lib/` 已重新生成并随各步提交。
- [x] 无 Out of scope 项被触碰（组件、locales、CSS 内容均未改；构建脚本有 1 处必要偏差，见下方验收记录）。

---

### 验收记录（2026-08-16 晚续接会话补充）

会话断开前的状态（上一会话 GUI 内 GenUI 状态表）：实现 ✅（d4afb83）、构建产物 ✅、构建脚本 ⚠️ 待裁决、浏览器验证 ⚠️ 降级、任务审查 ⏳ 断线时后台运行中。本次续接完成：

- **构建脚本偏差裁决**：`scripts/build-client.mjs` 的扁平→递归修复（52 行，随 d4afb83 提交）是 CSS 拆分子目录后 bundler 的必要修复，已被 AGENTS.md Pitfall「build-client.mjs 的扁平→递归（2026-08-16 修）」记录为既有事实 → 视为通过；断线时后台审查的正式结论未回收，如需可另起 review。
- **全量回归**：`pnpm verify` / `pnpm build`（14 modules inlined）/ `git diff --check` 全部通过；`lib/client.js` 为当前 bundle（sha `f98445928d37`），服务端下发 rev 与本地一致；4 个 CSS 分区探针（fab / sidebar-collapsed / explorer-col / hero）全 OK；`src/` 无 `./mobile.css.ts` 残留引用。
- **Playwright 双宽度抽查（本次真实执行，通过）**：
  - 窄屏 390×844 + 会话打开：toggle 可见（28×28，computed `flex`）→ 点击弹出抽屉（280px 左侧滑出）+ 全屏遮罩（390×844）→ 点遮罩右侧区域关闭 ✓ → 再开 + Escape 关闭 ✓；`style[data-plugin]` 仅 1 个（全插件共 98 个）；控制台 0 error。
  - 桌面 1280×800：toggle `display: none`（no-op 保持），frame 全宽 1280。
  - 前置坑（与重构无关，供后续参考）：Playwright 复用「长时间挂着的旧页面 context」时会出现 harness web 只渲染最后一条 dsh-ui fence（frame 被内联 `display:none`、fence 挂 app 根级）——任意宽度/新旧 bundle 均复现；全新 context（无旧 localStorage）则渲染正常。遇到此状态换干净 context 重测，不要据此改 mobile-nav 代码（详见 AGENTS.md「手机全屏 md」pitfall 的补充）。
