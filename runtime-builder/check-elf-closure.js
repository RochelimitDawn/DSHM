#!/usr/bin/env node
/* 构建期 ELF 依赖闭包校验。
 * 从 jniLibs 可执行与动态库出发递归解析 ELF64 的 DT_NEEDED，
 * 任何 SONAME 找不到提供者就让构建失败（并断言入口是目标架构）。
 * 用法: node check-elf-closure.js <jniLibs-dir> <usr-lib-dir> <arch>
 */
const fs = require('fs');
const path = require('path');

const [nativeDir, usrLibDir, arch] = process.argv.slice(2);
if (!nativeDir || !usrLibDir || !arch) {
  console.error('用法: node check-elf-closure.js <jniLibs-dir> <usr-lib-dir> <aarch64|x86_64>');
  process.exit(2);
}

const EXPECTED_MACHINE = { aarch64: 183, x86_64: 62 }; // EM_AARCH64 / EM_X86_64

// Android bionic 系统库：由 Android 系统提供，运行时树里没有，exec 时由系统加载。
// Termux 二进制（aarch64）链接它们，构建期校验必须豁免，否则误报缺失。
const SYSTEM_SONAMES = new Set([
  'libc.so', 'libm.so', 'libdl.so', 'libdl_android.so', 'liblog.so',
  'libandroid.so', 'libstdc++.so', 'libz.so', 'libthread_db.so',
  'libbacktrace.so', 'libbase.so', 'libunwindstack.so', 'libc++.so',
])

function parseElf(file) {
  const fd = fs.openSync(file, 'r');
  try {
    const head = Buffer.alloc(64);
    if (fs.readSync(fd, head, 0, 64, 0) < 64) return null;
    if (head.toString('ascii', 0, 4) !== '\x7fELF') return null;
    const is64 = head[4] === 2;
    if (!is64) return { is64: false, machine: head.readUInt16LE(18), needed: [] };
    const e_machine = head.readUInt16LE(18);
    const e_phoff = Number(head.readBigUInt64LE(32));
    const e_phentsize = head.readUInt16LE(54);
    const e_phnum = head.readUInt16LE(56);
    const loads = []; // { offset, vaddr, filesz } — PT_LOAD，用于 vaddr → 文件偏移换算
    let strTabAddr = 0n;
    let strSz = 0;
    const neededOffsets = [];
    for (let i = 0; i < e_phnum; i++) {
      const ph = Buffer.alloc(56);
      if (fs.readSync(fd, ph, 0, 56, e_phoff + i * e_phentsize) < 56) break;
      const p_type = ph.readUInt32LE(0);
      const p_offset = Number(ph.readBigUInt64LE(8));
      const p_vaddr = Number(ph.readBigUInt64LE(16));
      const p_filesz = Number(ph.readBigUInt64LE(32));
      if (p_type === 1) loads.push({ offset: p_offset, vaddr: p_vaddr, filesz: p_filesz }); // PT_LOAD
      if (p_type !== 2) continue; // PT_DYNAMIC
      const dyn = Buffer.alloc(p_filesz);
      if (fs.readSync(fd, dyn, 0, p_filesz, p_offset) < p_filesz) break;
      for (let d = 0; d + 16 <= dyn.length; d += 16) {
        const tag = Number(dyn.readBigUInt64LE(d));
        const val = dyn.readBigUInt64LE(d + 8);
        if (tag === 5) strTabAddr = val; // DT_STRTAB（虚拟地址）
        else if (tag === 10) strSz = Number(val); // DT_STRSZ
        else if (tag === 1) neededOffsets.push(Number(val)); // DT_NEEDED（.dynstr 内偏移）
      }
    }
    const vaddrToFile = (addr) => {
      for (const seg of loads) {
        if (addr >= seg.vaddr && addr < seg.vaddr + seg.filesz) return seg.offset + (addr - seg.vaddr);
      }
      return -1;
    };
    const names = [];
    if (strTabAddr > 0n && strSz > 0) {
      const dynStrFileOff = vaddrToFile(Number(strTabAddr));
      if (dynStrFileOff >= 0) {
        const str = Buffer.alloc(strSz);
        try {
          if (fs.readSync(fd, str, 0, strSz, dynStrFileOff) === strSz) {
            for (const off of neededOffsets) {
              if (off >= strSz) continue;
              const end = str.indexOf(0, off);
              if (end > off) names.push(str.toString('ascii', off, end));
            }
          }
        } catch (_) { /* .dynstr 读取失败按无依赖处理 */ }
      }
    }
    return { is64: true, machine: e_machine, needed: names };
  } finally {
    fs.closeSync(fd);
  }
}

// 收集所有提供者 SONAME（jniLibs + usr/lib + usr/lib 下一层子目录）
const providers = new Set();
function scanProviders(dir) {
  if (!fs.existsSync(dir)) return;
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    const st = fs.statSync(full);
    if (st.isDirectory()) scanProviders(full);
    else if (name.endsWith('.so') || name.includes('.so.')) providers.add(name);
  }
}
scanProviders(nativeDir);
scanProviders(usrLibDir);

const visited = new Set();
const missing = new Set();
const queue = [];
let entryMachine = null;

for (const name of fs.readdirSync(nativeDir)) {
  const full = path.join(nativeDir, name);
  if (!fs.statSync(full).isFile()) continue;
  const elf = parseElf(full);
  if (!elf) continue;
  if (entryMachine === null && elf.is64) entryMachine = elf.machine;
  queue.push([name, full]);
}

while (queue.length > 0) {
  const [label, full] = queue.shift();
  if (visited.has(label)) continue;
  visited.add(label);
  const elf = parseElf(full);
  if (!elf || !elf.is64) continue;
  for (const soname of elf.needed) {
    if (SYSTEM_SONAMES.has(soname)) continue
    if (providers.has(soname)) {
      // 递归解析该提供者的依赖（jniLibs 内或 usr/lib 内）
      for (const dir of [nativeDir, usrLibDir]) {
        const cand = path.join(dir, soname);
        if (fs.existsSync(cand) && !visited.has(soname)) queue.push([soname, cand]);
      }
    } else {
      missing.add(soname);
    }
  }
}

if (entryMachine !== null && entryMachine !== EXPECTED_MACHINE[arch]) {
  console.error(`[fail] ELF 闭包校验: 入口架构 e_machine=${entryMachine} 与目标 ${arch}（${EXPECTED_MACHINE[arch]}）不符`);
  process.exit(1);
}

if (missing.size > 0) {
  console.error(`[fail] ELF 闭包校验: ${missing.size} 个 SONAME 找不到提供者:`);
  for (const m of [...missing].sort()) console.error(`  - ${m}`);
  console.error('构建期一切正常、到设备 exec 才报 cannot find libxxx.so.N 的风险已被拦下；请补齐依赖库。');
  process.exit(1);
}

console.log(`[ok] ELF 闭包校验通过: ${visited.size} 个文件, ${providers.size} 个提供者, 依赖闭包完整`);
