#!/usr/bin/env python3
"""Termux 依赖闭包求解与 .deb 离线下载。

在 x64 Linux 上为 arm64 (bionic) 装配 Termux 包：读取官方 Packages 索引，
沿 Depends 递归求解依赖闭包，逐个下载 .deb 并解压到 prefix。
不需要模拟执行 arm64 二进制（纯 tar 解包）。
"""
import gzip
import os
import subprocess
import sys
import urllib.request

REPO = "https://packages.termux.dev/apt/termux-main"
ARCH = "aarch64"


def fetch(url: str, binary: bool = False) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "siliconleap-runtime-builder"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        data = resp.read()
    return data


def parse_packages(raw: bytes):
    """解析 Packages 索引为 {name: {fields}}。"""
    text = raw.decode("utf-8", errors="replace")
    stanzas = []
    cur = {}
    for line in text.splitlines():
        if line.strip() == "":
            if cur:
                stanzas.append(cur)
                cur = {}
            continue
        if line[0].isspace():
            continue
        key, _, value = line.partition(":")
        cur[key.strip()] = value.strip()
    if cur:
        stanzas.append(cur)
    return {s.get("Package"): s for s in stanzas if s.get("Package")}


def clean_dep(dep: str) -> str:
    """去掉版本约束，返回包名。"""
    name = dep.split("(", 1)[0].strip()
    return name


def resolve(packages: dict, roots: list[str]) -> list[str]:
    """BFS 求解依赖闭包，返回有序包名列表（根在前）。"""
    order: list[str] = []
    seen: set[str] = set()
    queue = list(roots)
    while queue:
        name = queue.pop(0)
        if name in seen:
            continue
        stanza = packages.get(name)
        if not stanza:
            print(f"[warn] 包不存在（跳过）：{name}")
            seen.add(name)
            continue
        seen.add(name)
        order.append(name)
        dep_line = stanza.get("Depends", "")
        for group in dep_line.split(","):
            group = group.strip()
            if not group:
                continue
            alternatives = [clean_dep(d) for d in group.split("|")]
            chosen = None
            for alt in alternatives:
                if alt in packages:
                    chosen = alt
                    break
            if chosen:
                queue.append(chosen)
            else:
                print(f"[warn] {name} 依赖 {group} 不可解析，跳过")
    return order


def extract_deb(deb_path: str, dest: str) -> None:
    """解包 .deb 并去掉 Termux 编译前缀 `data/data/com.termux/files/usr`（6 级）。"""
    proc = subprocess.Popen(
        ["dpkg-deb", "--fsys-tarfile", deb_path],
        stdout=subprocess.PIPE,
    )
    subprocess.run(
        ["tar", "-xf", "-", "--strip-components=6", "-C", dest],
        stdin=proc.stdout,
        check=True,
    )
    proc.wait()
    if proc.returncode != 0:
        raise RuntimeError(f"dpkg-deb --fsys-tarfile 失败: {deb_path}")


OLD_PREFIX = "/data/data/com.termux/files/usr"


def process_symlinks(prefix: str) -> None:
    """处理 prefix 下的 SYMLINKS.txt，重建为可迁移的符号链接后删除清单。"""
    txt = os.path.join(prefix, "SYMLINKS.txt")
    if not os.path.exists(txt):
        return
    created = 0
    for raw in open(txt, encoding="utf-8"):
        line = raw.rstrip("\n")
        if "←" not in line:
            continue
        target, link_rel = line.split("←", 1)
        link_rel = link_rel.lstrip("./")
        link_path = os.path.join(prefix, link_rel)
        os.makedirs(os.path.dirname(link_path), exist_ok=True)
        if os.path.lexists(link_path):
            os.unlink(link_path)
        if target.startswith(OLD_PREFIX):
            real_target = os.path.join(prefix, target[len(OLD_PREFIX):].lstrip("/"))
            link_to = os.path.relpath(real_target, os.path.dirname(link_path))
        else:
            link_to = target
        os.symlink(link_to, link_path)
        created += 1
    os.remove(txt)
    print(f"    已重建 {created} 个符号链接")


def main() -> None:
    if len(sys.argv) < 3:
        print("用法: deps.py <pkg1> [pkg2...] <out_dir>")
        sys.exit(1)
    roots = sys.argv[1:-1]
    out_dir = sys.argv[-1]

    os.makedirs(out_dir, exist_ok=True)
    cache = os.environ.get("DEB_CACHE", "")
    if cache:
        os.makedirs(cache, exist_ok=True)
    index_url = f"{REPO}/dists/stable/main/binary-{ARCH}/Packages.gz"
    print(f"下载包索引 {index_url}")
    raw = fetch(index_url)
    packages = parse_packages(gzip.decompress(raw))
    print(f"索引共 {len(packages)} 个包")

    order = resolve(packages, roots)
    print("依赖闭包（%d 个）：" % len(order))
    for n in order:
        stanza = packages[n]
        print(f"  {n} = {stanza.get('Version')}")

    cache = os.environ.get("DEB_CACHE", "")
    versions = {}
    for name in order:
        stanza = packages[name]
        versions[name] = stanza.get("Version", "")
        filename = stanza["Filename"]
        url = f"{REPO}/{filename}"
        local = os.path.join(cache or out_dir, f"{name}.deb")
        if not os.path.exists(local):
            print(f"下载 {name} ({stanza.get('Size')}B) ...")
            data = fetch(url)
            with open(local, "wb") as fh:
                fh.write(data)
        print(f"解压 {name} ...")
        extract_deb(local, out_dir)
        process_symlinks(out_dir)

    import json
    with open(os.path.join(out_dir, "versions.json"), "w", encoding="utf-8") as fh:
        json.dump(versions, fh, ensure_ascii=False, indent=2)
    print("完成，版本清单：", versions)


if __name__ == "__main__":
    main()
