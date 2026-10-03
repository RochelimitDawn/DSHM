#!/usr/bin/env python3
"""merge-clash-profile.py：合并用户订阅 profile 与内置规则模板，输出 mihomo 最终配置。

用法：merge-clash-profile.py <profile> <rules-template> <output> [mode]
- profile：用户订阅 YAML（App 下载写入 hostfs share/clash/config.yaml）
- rules-template：内置规则模板（/etc/dshm/clash-rules.yaml）
- output：/etc/dshm/clash.yaml
- mode：rule / global / direct（可选，覆盖模板 mode）

合并策略：profile 提供 proxies/proxy-groups；模板提供 mode/dns/tun/rules 骨架。
profile 缺 proxies 或 proxy-groups 引用不到节点时，兜底插入 DIRECT-only 组，
保证任何订阅开箱即用（规则引用的 PROXY 组必须存在）。
"""
import sys

try:
    import yaml
except ImportError:
    # minbase 无 PyYAML 时降级：纯文本模式覆盖（订阅原样透传，仅改 mode）
    def main():
        if len(sys.argv) < 4:
            print("usage: merge-clash-profile.py <profile> <template> <output> [mode]", file=sys.stderr)
            return 2
        profile, template, output = sys.argv[1:4]
        mode = sys.argv[4] if len(sys.argv) > 4 else None
        try:
            text = open(profile, encoding="utf-8").read()
        except OSError as e:
            print(f"profile read failed: {e}", file=sys.stderr)
            return 1
        # 透传 profile，规则/分组沿用订阅自带；模板仅用于提示
        with open(output, "w", encoding="utf-8") as f:
            f.write(text)
        return 0

else:
    def main():
        if len(sys.argv) < 4:
            print("usage: merge-clash-profile.py <profile> <template> <output> [mode]", file=sys.stderr)
            return 2
        profile_path, template_path, output_path = sys.argv[1:4]
        mode = sys.argv[4] if len(sys.argv) > 4 else None

        try:
            profile = yaml.safe_load(open(profile_path, encoding="utf-8"))
        except Exception as e:
            print(f"profile parse failed: {e}", file=sys.stderr)
            return 1
        # 校验 profile 必须是映射（损坏/非 YAML 订阅直接拒绝）
        if not isinstance(profile, dict):
            print("profile is not a YAML mapping", file=sys.stderr)
            return 1
        profile = profile or {}
        template = yaml.safe_load(open(template_path, encoding="utf-8")) or {}

        # 模板骨架打底：mode/dns/tun/rules；profile 的 proxies/proxy-groups 优先
        merged = dict(template)
        merged["proxies"] = profile.get("proxies") or []
        merged["proxy-groups"] = profile.get("proxy-groups") or []

        # 兜底：无 proxies 时插入 DIRECT-only 组（规则引用的 PROXY 组必须存在）
        names = [p.get("name", "") for p in merged["proxies"] if isinstance(p, dict)]
        if names:
            merged["proxy-groups"] = [
                {
                    "name": "PROXY",
                    "type": "url-test",
                    "proxies": names,
                    "url": "https://www.gstatic.com/generate_204",
                    "interval": 300,
                    "tolerance": 50,
                }
            ] + [g for g in merged["proxy-groups"] if isinstance(g, dict) and g.get("name") != "PROXY"]
        else:
            merged["proxy-groups"] = [{"name": "PROXY", "type": "select", "proxies": ["DIRECT"]}]
            merged["proxies"] = []

        # mode 覆盖（App 设置 rule/global/direct）
        if mode in ("rule", "global", "direct"):
            merged["mode"] = mode

        try:
            with open(output_path, "w", encoding="utf-8") as f:
                yaml.safe_dump(merged, f, allow_unicode=True, sort_keys=False)
        except OSError as e:
            print(f"output write failed: {e}", file=sys.stderr)
            return 1
        return 0


if __name__ == "__main__":
    sys.exit(main())
