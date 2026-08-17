# 设置弹窗 header 行紧凑成组 — 设计

日期：2026-08-16
状态：已批准（用户确认；验证由用户真机执行）

## 问题

设置弹窗顶部行（content header）当前用 `justify-content: space-between` 把
「打开配置文件」按钮与关闭 ✕ 拉到两端，中间留 ~144px 空隙；用户截图反馈
"空隙太大，尤其是打开配置文件和关闭的插号之间"。

## 方案

- header 内 actions 与 close 改为右侧成组：`justify-content: flex-end` +
  `gap: 8px`，顺序为 配置文件按钮（左）→ 关闭 ✕（右）。
- header `min-height: 40px` 保持（标准触摸目标）；按钮维持上轮压缩态
  （font 13px / padding 6px 12px）。
- 作用域：layout.css.ts 的 settings 规则已在 `@media (max-width: 1023px)`
  内（移动端专用），桌面设置弹窗（左右分栏）不受影响。

## 验证

- 用户真机（强刷后）确认：两控件间距 ≤12px、整体 header 紧凑。
- 桌面 ≥1024px 无变化（media 限定）。
