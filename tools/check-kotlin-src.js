#!/usr/bin/env node
/* Kotlin 源码自检：按字符遍历全部 .kt 文件，确认字符串、模板与注释都正确闭合。
 * Kotlin 的块注释可以嵌套——KDoc 里写一个含块注释起始标记的文本会打开一层嵌套注解，
 * 收尾标记只关掉内层，外层一直开着吃掉后面所有代码，编译器报出的却是一片
 * unresolved reference，定位代价极高。本检查器在编译前拦住这类问题。
 * 用法: node check-kotlin-src.js <src-dir> [...more-dirs]
 */
const fs = require('fs');
const path = require('path');

const dirs = process.argv.slice(2);
if (dirs.length === 0) {
  console.error('用法: node check-kotlin-src.js <src-dir> [...more-dirs]');
  process.exit(2);
}

function collectKtFiles(dir, out) {
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) collectKtFiles(full, out);
    else if (name.endsWith('.kt') || name.endsWith('.kts')) out.push(full);
  }
  return out;
}

function checkFile(file) {
  const src = fs.readFileSync(file, 'utf8');
  const errors = [];
  let i = 0;
  let line = 1;
  // 状态: code / line-comment / block-comment(深度) / string / raw-string / char
  let blockDepth = 0;
  let mode = 'code';
  let rawQuotes = 0;
  const fail = (msg) => errors.push(`${file}:${line}: ${msg}`);

  while (i < src.length) {
    const c = src[i];
    const next = src[i + 1];
    if (c === '\n') line++;
    if (mode === 'code') {
      if (c === '/' && next === '/') { mode = 'line'; i += 2; continue; }
      if (c === '/' && next === '*') { mode = 'block'; blockDepth = 1; i += 2; continue; }
      if (c === '"') {
        const m = /^"{3}/.exec(src.slice(i));
        if (m) { mode = 'raw'; rawQuotes = 3; i += 3; continue; }
        mode = 'string'; i += 1; continue;
      }
      if (c === "'") { mode = 'char'; i += 1; continue; }
      i += 1; continue;
    }
    if (mode === 'line') {
      if (c === '\n') mode = 'code';
      i += 1; continue;
    }
    if (mode === 'block') {
      if (c === '/' && next === '*') { blockDepth++; i += 2; continue; }
      if (c === '*' && next === '/') { blockDepth--; i += 2; if (blockDepth === 0) mode = 'code'; continue; }
      i += 1; continue;
    }
    if (mode === 'string') {
      if (c === '\\') { i += 2; continue; }
      if (c === '"') { mode = 'code'; i += 1; continue; }
      if (c === '$' && next === '{') {
        // 简单模板表达式平衡检查
        let depth = 1; let j = i + 2;
        while (j < src.length && depth > 0) {
          if (src[j] === '{') depth++;
          else if (src[j] === '}') depth--;
          else if (src[j] === '\n') line++;
          j++;
        }
        if (depth > 0) fail('字符串模板 "${" 未闭合');
        i = j; continue;
      }
      if (c === '\n') fail('普通字符串跨行（缺转义或引号未闭合）');
      i += 1; continue;
    }
    if (mode === 'raw') {
      if (c === '"' && next === '"') {
        const m = /^"{3}/.exec(src.slice(i));
        if (m) { mode = 'code'; i += 3; continue; }
      }
      i += 1; continue;
    }
    if (mode === 'char') {
      if (c === '\\') { i += 2; continue; }
      if (c === "'") { mode = 'code'; }
      i += 1; continue;
    }
  }

  if (mode === 'block') fail(`块注释未闭合（嵌套深度 ${blockDepth}）`);
  if (mode === 'string') fail('字符串未闭合');
  if (mode === 'raw') fail('raw 字符串未闭合');
  if (mode === 'char') fail('字符字面量未闭合');
  return errors;
}

let total = 0;
let failed = 0;
for (const dir of dirs) {
  const files = collectKtFiles(dir, []);
  total += files.length;
  for (const f of files) {
    const errors = checkFile(f);
    if (errors.length > 0) {
      failed++;
      for (const e of errors) console.error(e);
    }
  }
}

if (failed > 0) {
  console.error(`[fail] Kotlin 源码自检: ${failed} 个文件存在未闭合的字符串/模板/注释（共检查 ${total} 个文件）`);
  process.exit(1);
}
console.log(`[ok] Kotlin 源码自检通过: ${total} 个文件, 字符串/模板/注释全部正确闭合`);
