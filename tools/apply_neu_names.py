"""把 neu_names.json 里翻译好的物品名合并进游戏的翻译缓存。

必须**先退出游戏**再运行：TranslationManager 会把内存里的旧缓存写回
config/tongyongfanyi_cache.json，游戏运行中写入会被它覆盖掉。

用法：
    python tools/apply_neu_names.py                       # 自动找 .minecraft/versions 下的实例
    python tools/apply_neu_names.py --instance <实例目录>   # 指定某个版本实例
    python tools/apply_neu_names.py --check               # 只检查游戏是否已退出，不写
"""
import argparse
import json
import os
import re
import shutil
import sys
import time

NUM = re.compile(r"\d+(?:[.,]\d+)*(?:[kKmMbB]?)")
PREFIX = "auto>zh-CN|"


def discover_instances():
    """找本机 .minecraft/versions 下的所有版本实例（也可用 --dir 或 TYFY_INSTANCES 指定）。"""
    env = os.environ.get("TYFY_INSTANCES")
    if env:
        return [p for p in env.split(os.pathsep) if p]
    roots = [
        os.path.join(os.environ.get("APPDATA", ""), ".minecraft", "versions"),
        os.path.expanduser("~/.minecraft/versions"),
    ]
    found = []
    with_mod = []
    for root in roots:
        if root and os.path.isdir(root):
            for name in sorted(os.listdir(root)):
                path = os.path.join(root, name)
                if not os.path.isdir(os.path.join(path, "config")):
                    continue
                # 优先挑真的装了本模组（有缓存文件）的实例
                if os.path.isfile(os.path.join(path, "config", "tongyongfanyi_cache.json")):
                    with_mod.append(path)
                else:
                    found.append(path)
    return with_mod + found


INSTANCES = discover_instances()


def norm(s):
    return NUM.sub("#", s)


def game_running():
    try:
        import subprocess
        out = subprocess.run(
            ["tasklist", "/FI", "IMAGENAME eq javaw.exe"],
            capture_output=True, text=True, errors="replace").stdout
        return "javaw.exe" in out
    except Exception:
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="neu_names.json")
    ap.add_argument("--instance", default=None, help="版本实例目录，默认取自动探测到的第一个")
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()
    if args.instance is None:
        if not INSTANCES:
            sys.exit("没找到 .minecraft/versions 下的实例，请用 --instance <实例目录> 指定")
        args.instance = INSTANCES[0]

    if game_running():
        print("游戏还在运行，退出后再执行（否则写进去会被游戏覆盖）")
        if not args.check:
            sys.exit(1)
        return

    here = os.path.dirname(os.path.abspath(__file__))
    names = json.load(open(os.path.join(here, args.name), encoding="utf-8"))
    path = os.path.join(args.instance, "config", "tongyongfanyi_cache.json")
    cache = json.load(open(path, encoding="utf-8"))
    before = len(cache)

    existing = {k.split("|", 1)[-1].strip() for k in cache}
    added = skipped = 0
    for en, cn in names.items():
        if en in existing or norm(en) in existing:
            skipped += 1
            continue
        cache[PREFIX + norm(en)] = cn
        existing.add(norm(en))
        added += 1

    if args.check:
        print(f"可以写入：新增 {added} 条，已有 {skipped} 条（缓存 {before} -> {before + added}）")
        return

    backup = path + ".bak-" + time.strftime("%Y%m%d-%H%M%S")
    shutil.copy2(path, backup)
    json.dump(cache, open(path, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(f"已写入：{before} -> {len(cache)}（新增 {added}，跳过 {skipped}）")
    print(f"备份：{backup}")


if __name__ == "__main__":
    main()
