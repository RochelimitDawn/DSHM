#!/usr/bin/env node
/* 对已安装的 @deepseek-ai/dsh 运行时做针对性补丁（Android 适配）。
 * 用法: node patch_runtime.js <prefix>
 */
const fs = require('fs');
const path = require('path');

const prefix = process.argv[2];
if (!prefix) {
  console.error('用法: node patch_runtime.js <prefix>');
  process.exit(1);
}

let changes = 0;

function patchFile(rel, transform, label) {
  const target = path.join(prefix, rel);
  if (!fs.existsSync(target)) {
    console.log(`[warn] ${label}: 文件不存在，跳过`);
    return;
  }
  let src = fs.readFileSync(target, 'utf8');
  const result = transform(src);
  if (result === src) {
    console.log(`[warn] ${label}: 未匹配到目标语句，跳过`);
    return;
  }
  fs.writeFileSync(target, result);
  console.log(`[patch] ${label} 已应用`);
  changes++;
}

// Patch 1: subprocess-local 懒加载 node-pty，缺失时降级（保证应用总能启动）
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-subprocess-local/lib/index.js',
  (src) =>
    src.replace(
      /import \* as nodePty from "node-pty";/,
      `import { createRequire } from "node:module";
const __siliconleapRequire = createRequire(import.meta.url);
let _siliconleapNodePty = null;
const nodePty = new Proxy({}, { get(_t, prop) { if (prop === "spawn") { return (...args) => { if (!_siliconleapNodePty) { try { _siliconleapNodePty = __siliconleapRequire("node-pty"); } catch (e) { throw new Error("[siliconleap] node-pty 不可用（Android PTY 未编译），已降级: " + e.message); } } return _siliconleapNodePty.spawn(...args); }; } return undefined; } });`,
    ),
  'subprocess-local node-pty 懒加载',
);

// Patch 2: tool-fs-search 的 rg 路径允许 DSH_RG_PATH 覆盖
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-tool-fs-search/lib/index.js',
  (src) =>
    src.replace(
      /rgPathPromise \?\?= import\("@vscode\/ripgrep"\)\.then\(\(module\) => module\.rgPath\);/,
      'rgPathPromise ??= process.env.DSH_RG_PATH ? Promise.resolve(process.env.DSH_RG_PATH) : import("@vscode/ripgrep").then((module) => module.rgPath);',
    ),
  'tool-fs-search DSH_RG_PATH',
);

// Patch 3: koffi 原生 FFI 无 Android 平台产物，替换为 stub。
// 仅 dsh-sandbox-windows-acl（Windows 专用后端）引用 koffi，Android 上不会执行。
function writeFile(rel, content, label) {
  const target = path.join(prefix, rel);
  try {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, content);
    console.log(`[patch] ${label} 已写入`);
    changes++;
  } catch (e) {
    console.log(`[warn] ${label} 写入失败: ${e.message}`);
  }
}

const KOFFI_STUB_ESM = `function __siliconleapKoffiStub() {
  return new Proxy(function () {}, {
    get: () => __siliconleapKoffiStub(),
    apply: () => null,
    construct: () => ({}),
  });
}
export default __siliconleapKoffiStub();
`;

const KOFFI_STUB_CJS = `function __siliconleapKoffiStub() {
  return new Proxy(function () {}, {
    get: () => __siliconleapKoffiStub(),
    apply: () => null,
    construct: () => ({}),
  });
}
module.exports = __siliconleapKoffiStub();
`;

writeFile('lib/node_modules/koffi/index.js', KOFFI_STUB_ESM, 'koffi index.js stub');
writeFile('lib/node_modules/koffi/index.cjs', KOFFI_STUB_CJS, 'koffi index.cjs stub');

console.log(`[done] 共应用 ${changes} 处补丁`);
