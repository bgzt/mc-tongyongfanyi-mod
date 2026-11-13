"""把 neu_names.json 里翻译好的物品名合并进游戏的翻译缓存。

必须**先退出游戏**再运行：TranslationManager 会把内存里的旧缓存写回
config/tongyongfanyi_cache.json，游戏运行中写入会被它覆盖掉。

用法：
    python tools/apply_neu_names.py           # 默认写 versions/26.2-Fabric
    python tools/apply_neu_names.py --check   # 只检查游戏是否已退出，不写
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

INSTANCES = [
    r"C:\Users\bgzt1\AppData\Roaming\.minecraft\versions\26.2-Fabric",
]


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
    ap.add_argument("--instance", default=INSTANCES[0])
    ap.add_argument("--check", action="store_true")
    args = ap.parse_args()

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
