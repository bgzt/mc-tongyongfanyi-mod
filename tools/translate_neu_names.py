"""把 NEU 仓库里的物品名批量翻成中文，结果写入翻译缓存。

用法（在游戏实例目录下跑）：
    python tools/translate_neu_names.py --limit 20     # 先试 20 条看质量
    python tools/translate_neu_names.py                # 全量，断点续跑

设计要点：
- 串行提交（服务端 OLLAMA_NUM_PARALLEL=1），带小间隔，避免把游戏的翻译队列堵死
- 每 40 条落一次盘，中断后可续跑（已翻过的直接跳过）
- 结果先写 neu_names.json，最后由 --apply 合并进 config/tongyongfanyi_cache.json
"""
import argparse
import json
import os
import re
import sys
import time
import urllib.request

BASE = "http://127.0.0.1:11634"
MODEL = "tyfy-hymt2:1.8b"
NUM = re.compile(r"\d+(?:[.,]\d+)*(?:[kKmMbB])?")
CJK = re.compile(r"[\u4e00-\u9fa5]")

SYSTEM = (
    "你是 Hypixel SkyBlock 的物品名翻译器。把用户给的英文物品名译成简体中文，"
    "只输出中文译名，不要任何解释。\n"
    "规则：\n"
    "- 只输出译名本身，不要引号、书名号、括号注释、不要「译名：」这类前缀\n"
    "- 罗马数字等级（I II III IV V）原样保留，绝不能译成「我」\n"
    "- 数字、™ © ® 符号、版本号原样保留\n"
    "- NPC 人名音译；品牌谐音名（Sumsung©、AⒷiphone、Abicase）保留原文或音译，不要意译\n"
    "- 这是装备/物品/宠物/材料的名字，按游戏物品名的习惯译，不要译成一整句话\n"
    "- 不要加「的」「一个」这类多余的字\n"
)


def ask(text, timeout=25):
    body = json.dumps({
        "model": MODEL,
        "stream": False,
        "keep_alive": "60m",
        "think": False,
        "messages": [{"role": "system", "content": SYSTEM},
                     {"role": "user", "content": text}],
        "options": {"temperature": 0.1, "num_predict": 96, "num_ctx": 1536},
    }).encode()
    req = urllib.request.Request(BASE + "/api/chat", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        d = json.load(r)
    return (d.get("message", {}).get("content") or "").strip()


def clean(out, src):
    if not out:
        return None
    line = out.splitlines()[0].strip()
    line = re.sub(r'^(译文|译名|翻译|中文)[:：]\s*', '', line)
    line = line.strip('"“”\'「」《》 ')
    line = line.replace("§", "")
    if not line or not CJK.search(line):
        return None
    if line == src:
        return None
    # 汉字太少（模型把原文照抄回来）或者长得离谱（开始解释）
    if len(line) > max(12, len(src) * 2.5):
        return None
    if re.search(r"[A-Za-z]{4,}", line) and len(CJK.findall(line)) < 2:
        return None
    # 数字个数必须和原文一致，对不上说明漏了等级/年份
    if len(NUM.findall(src)) != len(NUM.findall(line)):
        return None
    return line


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--limit", type=int, default=0)
    ap.add_argument("--gap", type=float, default=0.03)
    ap.add_argument("--src", default="neu_rest.json")
    ap.add_argument("--out", default="neu_names.json")
    args = ap.parse_args()

    todo = json.load(open(args.src, encoding="utf-8"))
    done = {}
    if os.path.exists(args.out):
        done = json.load(open(args.out, encoding="utf-8"))
        print(f"续跑：已完成 {len(done)} 条")

    pending = [n for n in todo if n not in done]
    if args.limit:
        pending = pending[:args.limit]
    print(f"待翻 {len(pending)} 条（总 {len(todo)}）")

    t0 = time.time()
    failed = 0
    for i, name in enumerate(pending, 1):
        try:
            out = ask(name)
            cn = clean(out, name)
        except Exception as e:
            cn = None
            if i % 50 == 0:
                print(f"  请求异常: {e}", flush=True)
        if cn:
            done[name] = cn
        else:
            failed += 1
            done.setdefault("__failed__", {})
            if isinstance(done["__failed__"], dict):
                done["__failed__"][name] = out[:60] if 'out' in dir() else ""
        if i % 40 == 0 or i == len(pending):
            payload = {k: v for k, v in done.items() if k != "__failed__"}
            json.dump(payload, open(args.out, "w", encoding="utf-8"),
                      ensure_ascii=False, indent=0)
            rate = i / max(1e-6, time.time() - t0)
            eta = (len(pending) - i) / max(1e-6, rate)
            print(f"  {i}/{len(pending)}  失败 {failed}  {rate:.1f} 条/秒  剩余 {eta/60:.1f} 分",
                  flush=True)
        time.sleep(args.gap)

    payload = {k: v for k, v in done.items() if k != "__failed__"}
    json.dump(payload, open(args.out, "w", encoding="utf-8"), ensure_ascii=False, indent=0)
    print(f"完成：成功 {len(payload)} 条，失败 {failed} 条，用时 {(time.time()-t0)/60:.1f} 分")


if __name__ == "__main__":
    main()
