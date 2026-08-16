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

// Patch 4: apiproxy 的 native-path-opener 在 Android 上无实现（只支持
// darwin/win32/linux），点击"打开配置文件"会报晦涩的
// "native path opener is unsupported on android"。改为中文友好提示并附带
// 文件路径，方便用户在 DSHM 应用内或 adb 中查看。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-host-apiproxy/lib/index.js',
  (src) =>
    src.replace(
      /throw new Error\(`native path opener is unsupported on \$\{platform\}`\);/,
      `if (platform === "android") {
      throw new Error("Android 沙箱限制，无法调用系统应用打开该文件；路径：" + path + "。请在 DSHM 应用内或使用 adb 查看。");
    }
    throw new Error(\`native path opener is unsupported on \${platform}\`);`,
    ),
  'apiproxy Android opener 中文提示',
);

// Patch 5: session-persistence-jsonl 用 link() 发布会话日志（.jsonl.zstd）。
// Android SELinux 拒绝 app 对 app_data_file 执行 link 操作 → EACCES，
// 会话无法保存导致"本轮运行失败"。改为同一目录内原子 rename
// （app_data_file 允许 rename，语义等价且同样持久）。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js',
  (src) =>
    src
      .replace(
        /import \{ link, mkdir,/,
        'import { rename, mkdir,',
      )
      .replace(
        /await link\(tmp, finalPath\);/,
        'await rename(tmp, finalPath);',
      ),
  'session-persistence link→rename',
);

// Patch 6: attachment-local 存储附件时同样用 link() 发布对象文件，Android
// SELinux 拒绝 app 对 app_data_file 执行 link → EACCES。改为 rename
// （content-addressed 附件同 sha256 内容相同，覆盖无害）。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-attachment-local/lib/index.js',
  (src) =>
    src
      .replace(
        /import \{ chmod, link, mkdir,/,
        'import { chmod, rename, mkdir,',
      )
      .replace(
        /await link\(temporary, target\);/,
        'await rename(temporary, target);',
      ),
  'attachment-local link→rename',
);

// Patch 7: bash-local 用 DSH_BASH_PATH 直接执行 nativeLibraryDir/libbash.so。
// Android SELinux 禁止从 app 数据目录（filesDir）执行二进制，filesDir/bin
// 下的 bash 符号链接 exec 会 EACCES；nativeLibraryDir 与 node 服务一样可执行。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-bash-local/lib/index.js',
  (src) => src.replaceAll('"bash",', '(process.env.DSH_BASH_PATH || "bash"),'),
  'bash-local DSH_BASH_PATH',
);

// Patch 8: bash-sandbox 的 confine argv 同样用 DSH_BASH_PATH。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-bash-sandbox/lib/index.js',
  (src) => src.replaceAll('"bash",', '(process.env.DSH_BASH_PATH || "bash"),'),
  'bash-sandbox DSH_BASH_PATH',
);

// Patch 9: sandbox-local 在 Android（无 bubblewrap/Landlock）下降级为
// "none" runner —— 不再抛 SandboxUnavailableError，命令直接执行
// （Android 本身已受系统沙箱约束）。同时 confine() 对 none runner 直接
// 返回原 argv，不包裹 runner 参数。
patchFile(
  'lib/node_modules/@deepseek-ai/dsh-sandbox-local/lib/index.js',
  (src) =>
    src
      .replace(
        'if (first === void 0) return "unavailable";',
        'if (first === void 0) return { runner: "none", enforcement: "none" };',
      )
      .replace(
        /(\t+)return "unavailable";/,
        '$1return { runner: "none", enforcement: "none" };',
      )
      .replace(
        /const selected = this\.selectRunner\(policy\.mode\);\n(\t+)return \{\n\s*argv: \[\n\s*\.\.\.this\.runnerArgv\(selected\.runner, policy\),/,
        'const selected = this.selectRunner(policy.mode);\n$1if (selected.runner === "none") {\n$1\treturn { argv: [...argv], enforcement: "none", denialSignatures: [], runnerFailureRules: [] };\n$1}\n$1return {\n$1\targv: [\n$1\t\t...this.runnerArgv(selected.runner, policy),',
      ),
  'sandbox-local Android 无沙箱降级（none runner）',
);

// Patch 10: Debian 子系统（proot）——bash-local / bash-sandbox / terminal-bash
// 的 bash argv 前缀注入 proot 包裹（DSH_SUBSYSTEM_ARGV，JSON 数组）。
// 应用侧 TermuxEnv.serverEnv 注入该变量；子系统未安装/开关关闭时为空串，
// 回退原生 bash（与旧行为一致）。DSH 服务重启后环境变量更新生效。
const SUBSYS_SPREAD =
  '...(process.env.DSH_SUBSYSTEM_ARGV ? JSON.parse(process.env.DSH_SUBSYSTEM_ARGV) : [(process.env.DSH_BASH_PATH || "bash")])';

patchFile(
  'lib/node_modules/@deepseek-ai/dsh-bash-local/lib/index.js',
  (src) =>
    src
      .replaceAll('"bash",', '(process.env.DSH_BASH_PATH || "bash"),')
      .replace(
        /return this\.runArgv\(spec, \[\n(\s+)\(process\.env\.DSH_BASH_PATH \|\| "bash"\),\n(\s+)"-c",/,
        `return this.runArgv(spec, [\n$1${SUBSYS_SPREAD},\n$2"-c",`,
      ),
  'bash-local DSH_BASH_PATH + 子系统 proot 包裹',
);

patchFile(
  'lib/node_modules/@deepseek-ai/dsh-bash-sandbox/lib/index.js',
  (src) =>
    src
      .replaceAll('"bash",', '(process.env.DSH_BASH_PATH || "bash"),')
      .replace(
        /return this\.ctx\.sandbox\.confine\(\[\n(\s+)\(process\.env\.DSH_BASH_PATH \|\| "bash"\),\n(\s+)"-c",/,
        `return this.ctx.sandbox.confine([\n$1${SUBSYS_SPREAD},\n$2"-c",`,
      ),
  'bash-sandbox DSH_BASH_PATH + 子系统 proot 包裹',
);

patchFile(
  'lib/node_modules/@deepseek-ai/dsh-terminal-bash/lib/index.js',
  (src) =>
    src.replace(
      /const argv = \[config\.shellPath, \.\.\.config\.shellArgs\];/,
      'const argv = [...(process.env.DSH_SUBSYSTEM_ARGV ? JSON.parse(process.env.DSH_SUBSYSTEM_ARGV) : [config.shellPath]), ...config.shellArgs];',
    ),
  'terminal-bash 子系统 proot 包裹',
);

// Patch 3: koffi 原生 FFI 无 Android 平台产物，替换为 stub。
// 仅 dsh-sandbox-windows-acl（Windows 专用后端）引用 koffi，Android 上不会执行。
// dsh-sandbox-local 顶层静态 import windows-acl，导致其模块顶层执行
// koffi.struct("STARTUPINFOW"|"PROCESS_INFORMATION", ...) 并立即读取 .size 做 ABI 断言
// （types-*.js 第 110 行附近）。stub 必须让这两个调用返回带正确 size 的对象，
// 否则 null.size 抛 TypeError，整个插件树加载失败。
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
    get: (_t, prop) => {
      if (prop === "then") return undefined;
      return __siliconleapKoffiStub();
    },
    apply: (_t, _thisArg, args) => {
      if (args[0] === "STARTUPINFOW") return { size: 104 };
      if (args[0] === "PROCESS_INFORMATION") return { size: 24 };
      return null;
    },
    construct: () => ({}),
  });
}
export default __siliconleapKoffiStub();
`;

const KOFFI_STUB_CJS = `function __siliconleapKoffiStub() {
  return new Proxy(function () {}, {
    get: (_t, prop) => {
      if (prop === "then") return undefined;
      return __siliconleapKoffiStub();
    },
    apply: (_t, _thisArg, args) => {
      if (args[0] === "STARTUPINFOW") return { size: 104 };
      if (args[0] === "PROCESS_INFORMATION") return { size: 24 };
      return null;
    },
    construct: () => ({}),
  });
}
module.exports = __siliconleapKoffiStub();
`;

writeFile('lib/node_modules/koffi/index.js', KOFFI_STUB_ESM, 'koffi index.js stub');
writeFile('lib/node_modules/koffi/index.cjs', KOFFI_STUB_CJS, 'koffi index.cjs stub');

console.log(`[done] 共应用 ${changes} 处补丁`);
