#!/usr/bin/env python3
"""收集给定可执行文件的动态库依赖闭包，复制到输出目录（jniLibs 用）。

用法: collect_libs.py <prefix> <out_dir> <bin1> [bin2...]
prefix 为 runtime 的 usr 目录；bins 相对于 prefix/bin。
系统库（libc/libdl/libm 等）跳过，由 Android 提供。
"""
import os
import re
import shutil
import subprocess
import sys

SYSTEM_LIBS = {
    # bionic 系统库由 Android 提供，跳过
    "libc.so", "libdl.so", "libm.so", "liblog.so", "libcutils.so",
    "libandroid.so", "libstdc++.so", "linker", "linker64",
}


def needed(path: str):
    try:
        out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True, timeout=30)
    except Exception:
        return []
    for line in out.stdout.splitlines():
        m = re.search(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", line)
        if m:
            yield m.group(1)


def find_lib(prefix_lib: str, lib: str) -> str | None:
    direct = os.path.join(prefix_lib, lib)
    if os.path.exists(direct):
        return direct
    base = lib.split(".so")[0]
    if os.path.isdir(prefix_lib):
        for f in os.listdir(prefix_lib):
            if f.startswith(base) and ".so" in f:
                return os.path.join(prefix_lib, f)
    return None


def main() -> None:
    prefix, out = sys.argv[1], sys.argv[2]
    bins = sys.argv[3:]
    prefix_lib = os.path.join(prefix, "lib")
    os.makedirs(out, exist_ok=True)
    copied = set()

    def copy_lib(lib: str, indent: int = 0) -> None:
        if lib in copied:
            return
        copied.add(lib)
        src = find_lib(prefix_lib, lib)
        if not src:
            if lib not in SYSTEM_LIBS:
                print(f"[warn] 缺失动态库: {lib}")
            return
        dst = os.path.join(out, os.path.basename(src))
        if not os.path.exists(dst):
            shutil.copy2(src, dst)
            print(f"  {'  ' * indent}复制 {os.path.basename(src)}")
        for dep in needed(src):
            copy_lib(dep, indent + 1)

    for b in bins:
        bin_path = os.path.join(prefix, "bin", b)
        if not os.path.exists(bin_path):
            print(f"[warn] 可执行文件不存在: {bin_path}")
            continue
        print(f"==> {b}: {needed(bin_path)}")
        for lib in needed(bin_path):
            copy_lib(lib)
    print(f"完成，共复制 {len(copied)} 个动态库到 {out}")


if __name__ == "__main__":
    main()
