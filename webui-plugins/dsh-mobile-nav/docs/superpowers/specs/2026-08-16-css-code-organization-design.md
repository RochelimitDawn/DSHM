# dsh-mobile-nav CSS 与代码整理设计

日期：2026-08-16 · 状态：已批准（折中案 + 分两步走）

## 背景

`dsh-mobile-nav` 是纯客户端 DSH Web UI 插件（窄屏适配）。当前痛点：

- `src/client/mobile.css.ts`：1002 行单一 CSS 模板字符串，虽有分区注释，但改一处插件兼容要上下翻很久。
- `src/client/index.tsx`：304 行，6 个 effect（样式注入、状态栏、aionui 标记 ×2、统计行、动画重放）+ 3 个 slot 注册挤在一起。

目标：按「为什么存在」分界拆分（而非按文件内注释位置机械切片），**零行为变化**，拆分可独立验证、可独立回退。

## 范围

### In scope

- `mobile.css.ts` 拆为 4 个文件 + `styles/index.ts` 拼接导出。
- `index.tsx` 的 5 个 effect 拆为 3 个文件；`index.tsx` 缩为纯编排。
- `AGENTS.md` Architecture 节同步更新。
- 每步独立提交、独立验证。

### Out of scope（明确不做）

- 组件文件（`MobileNavOverlay.tsx` 等）与 `locales.ts` 不动。
- 不新增/删除/改写任何 CSS 规则、选择器、注释内容；不合并重复规则、不重命名选择器。
- 不改 effect 内部逻辑；不合并/删除 effect。
- 不改构建脚本 `scripts/build-client.mjs`（打包器递归内联相对模块，已确认支持多文件）。
- 不引入测试框架；不新增运行时依赖。

## 目标结构

```
src/client/
  index.tsx                 # 编排层：locale 注册 + 样式注入 + 3 个 install 调用 + 3 个 slot 注册（~80 行）
  MobileNavToggle.tsx       # 不变
  MobileNavOverlay.tsx      # 不变
  MobileDrawerFooter.tsx    # 不变
  locales.ts                # 不变
  effects/
    phone-chrome.ts         # 状态栏 theme-color + viewport + zoom guard（原第 2 effect）
    aionui-compat.ts        # explorer 关闭标记 + preview 开关标记 + 浮层升起动画重放（原第 3、4、6 effect 合并）
    stats-line.ts           # 统计行标记 + TPS 折叠（原第 5 effect）
  styles/
    index.ts                # 按序拼接 4 部分，导出 MOBILE_CSS（唯一注入点）
    base.css.ts             # ~130 行：基础控件（footer actions / 浮动按钮 / 遮罩 / settings 入场 / preview rise）
    layout.css.ts           # ~455 行：移动布局（chrome / AppFrame / 会话文本 / composer 底栏 / header / popovers / settings 弹窗）
    compat.css.ts           # ~445 行：dsh-web-ui 家族兼容（family base / explorer / preview 含全屏切换 / task board / market / usage-stats / settings polish / footer polish / pet / stats line）
    misc.css.ts             # ~90 行：hero 输入区 + 平板/宽屏 + 桌面 no-op（三段按原序）
```

## 设计决策

1. **单一 `<style data-plugin>` 标签注入不变**。`styles/index.ts` 按 `base → layout → compat → misc` 顺序 `join('\n')` 导出；该顺序与现状字节序一致（现状顺序为 base → layout → compat → composer → tablet → desktop），级联关系零扰动。
2. **文件边界 = 现有分区注释行**，注释保留在各文件顶部；迁移为纯机械搬移，不增删改任何 CSS 字节（边界换行由实现时逐字节对齐，diff 兜底）。
3. **每个 effect 文件导出 `installX(ctx: ClientContext): void`**，内部自带 `ctx.effect(() => {...}, label)`（label 与实现同处一地，符合 AGENTS.md 长生命周期资源必须在 `ctx.effect` + label 中创建的约定）。`index.tsx` 按序调用。
4. **aionui 三个 effect 合并为一个文件**：explorer 标记、preview 标记、升起动画重放同域同机制（dsh-web-ui 的 explorer/preview 列显隐），合并后一处看完；内部按原顺序排列，各自保留原注释与 label。
5. **客户端导入纯净性保持**：effect 文件只 type-only import `ClientContext`；无运行时跨包 import。
6. **零行为变化**：不增删改任何 CSS 规则/选择器/effect 逻辑；slot 注册、locale、样式注入点原样。

## 验证方案（无测试框架，用证据代替断言）

按「分两步走」推进，每步独立提交、独立验证：

### Step 1：CSS 拆分（风险最低，先行）

1. 构建前基线：从 `lib/client.js` 提取 `MOBILE_CSS` 原文（正则抽取模板字符串内容）存 `/tmp/mobile-css-baseline.txt`。
2. 拆 4 文件 + `styles/index.ts`，`index.tsx` 的 import 改为 `./styles/index.ts`。
3. `pnpm verify` → 类型检查通过。
4. `pnpm build` → 重新提取 `MOBILE_CSS` → 与基线 **逐字节 diff 必须为空**（含边界换行）；若有差异，修正拼接边界直至为空。
5. playwright 打开本地 DSH web（http://127.0.0.1:3080），窄屏 ~390px 与桌面 ≥1024px 抽查：抽屉开关、浮动按钮、设置弹窗、explorer/preview 浮层无回归；强刷页面确认新 bundle rev（对照 AGENTS.md 的 rev 检查法）。
6. 提交 `refactor(mobile): split mobile css into themed files`（含 `lib/` 再生成产物）。

### Step 2：effects 拆分

1. 建 `effects/` 三文件，`index.tsx` 改为 3 个 `installX(ctx)` 调用。
2. `pnpm verify` + `pnpm build` 通过。
3. 浏览器复验同上（重点：设置弹窗关闭后样式标签清理、抽屉关闭启发式、preview 标记联动）。
4. 提交 `refactor(mobile): extract client effects into modules`（含 `lib/`）。

### 收尾

- `AGENTS.md` Architecture 节：`mobile.css.ts` → `styles/` 与 `effects/` 描述；Conventions 补充 `refactor(mobile):` 前缀。
- 提交 `docs: update AGENTS.md for split styles/effects layout`。

## 风险与缓解

| 风险 | 缓解 |
|---|---|
| CSS 拼接顺序扰动级联 | 拼接顺序与现状字节序一致（构造保证）+ 逐字节 diff |
| 无测试框架导致回归漏检 | 字节 diff（CSS 全量）+ playwright 双宽度抽查（行为） |
| 历史坑：残留 style 标签 / 旧 bundle | 单一 style 标签保持；验证时强刷并核对 rev |
| 迁移时误改内容 | 纯机械搬移；diff 非空即停，先修边界再继续 |

## 成功标准

- [ ] Step 1、Step 2 各自 `pnpm verify` + `pnpm build` 通过，提交独立。
- [ ] Step 1 后 `lib/client.js` 中 CSS 与基线逐字节一致。
- [ ] 窄屏与桌面 playwright 抽查无回归（抽屉、浮动按钮、设置弹窗、浮层）。
- [ ] `AGENTS.md` 已同步；无 Out of scope 项被触碰。
