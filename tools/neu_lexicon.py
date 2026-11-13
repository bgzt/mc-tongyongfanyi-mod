"""NEU 物品名 → 中文：词表 + 组合规则。

思路：物品名是「修饰词 + 主体 + 类型后缀」的堆叠（Enchanted Diamond Sword），
按词表逐词替换就能得到可用的中文，比逐条机翻稳定，也不会漏条。

- 词表命中的词 → 中文
- 没命中的词（NPC 名、品牌谐音、生造词）→ 保留原文
- 类型后缀（Skin / Minion / Gemstone …）单独一张表，优先匹配
- SPECIAL 里是整条替换：语序不是简单堆叠的、或者需要官方译名的
"""

# ── 类型后缀：出现在名字末尾，翻译时放在最后 ──
TYPES = {
    "skin": "皮肤", "minion": "仆从", "shard": "碎片", "potion": "药水",
    "gemstone": "宝石", "rune": "符文", "badge": "徽章", "helmet": "头盔",
    "chestplate": "胸甲", "leggings": "护腿", "boots": "靴子", "sword": "剑",
    "bow": "弓", "axe": "斧", "pickaxe": "镐", "shovel": "铲", "hoe": "锄",
    "ring": "戒指", "necklace": "项链", "talisman": "护符", "belt": "腰带",
    "cloak": "斗篷", "gloves": "手套", "gauntlet": "护手", "bracelet": "手链",
    "mask": "面具", "hat": "帽子", "staff": "法杖", "wand": "魔杖", "rod": "钓竿",
    "sack": "袋子", "scroll": "卷轴", "dye": "染料", "disc": "唱片",
    "fragment": "碎片", "orb": "宝珠", "artifact": "神器", "relic": "遗物",
    "catalyst": "催化剂", "core": "核心", "engine": "引擎", "chip": "芯片",
    "vial": "瓶", "elixir": "药剂", "bait": "鱼饵", "hook": "鱼钩", "line": "鱼线",
    "sinker": "沉子", "book": "书", "trophy": "奖杯", "medal": "奖章",
    "token": "代币", "ticket": "票券", "candy": "糖果", "cookie": "饼干",
    "cake": "蛋糕", "stew": "炖菜", "burger": "汉堡", "sandwich": "三明治",
    "pants": "裤子", "trousers": "长裤", "hoodie": "连帽衫", "armor": "护甲",
    "backpack": "背包", "bucket": "桶", "bottle": "瓶", "lamp": "灯",
    "candle": "蜡烛", "chair": "椅子", "bench": "长椅", "table": "桌子",
    "door": "门", "fence": "栅栏", "stairs": "楼梯", "slab": "台阶",
    "pane": "玻璃板", "bricks": "砖块", "brick": "砖", "block": "方块",
    "log": "原木", "wood": "木头", "carpet": "地毯", "chest": "箱子",
    "hopper": "漏斗", "beacon": "信标", "minecart": "矿车", "sofa": "沙发",
    "doll": "玩偶", "plushie": "毛绒玩具", "toy": "玩具", "snowglobe": "雪花球",
    "glyph": "符纹", "prism": "棱镜", "tesseract": "超立方体", "cube": "方块",
    "anchor": "锚", "trap": "陷阱", "huntrap": "狩猎陷阱", "key": "钥匙",
    "map": "地图", "compass": "指南针", "clock": "时钟", "phone": "电话",
    "grimoire": "魔法书", "scythe": "镰刀", "sickle": "镰刀", "blade": "刀刃",
    "claws": "利爪", "claw": "爪", "fang": "獠牙", "stinger": "毒刺",
    "tooth": "牙齿", "skull": "头骨", "bone": "骨头", "eye": "眼睛",
    "head": "头颅", "shell": "壳", "pelt": "毛皮", "soul": "灵魂",
    "essence": "精华", "distillate": "蒸馏液", "powder": "粉末",
    "dust": "粉尘", "ore": "矿石", "pearl": "珍珠",
}

# ── 普通词 ──
WORDS = {
    # 品质 / 状态
    "enchanted": "附魔", "perfect": "完美", "flawless": "无瑕", "flawed": "有瑕",
    "rough": "粗糙", "fine": "精良", "hardened": "硬化", "refined": "精炼",
    "polished": "抛光", "reinforced": "强化", "advanced": "高级", "super": "超级",
    "great": "伟大", "master": "大师", "royal": "皇家", "prime": "至尊",
    "ancient": "远古", "primordial": "原初", "vanquished": "被征服的",
    "rekindled": "重燃", "shimmering": "微光", "smooth": "光滑", "sharp": "锋利",
    "hard": "坚硬", "true": "真实", "null": "虚空", "wet": "潮湿", "hot": "炙热",
    "cold": "寒冷", "old": "旧", "new": "新", "young": "幼年", "baby": "幼年",
    "small": "小型", "large": "大型", "tiny": "微型", "giant": "巨型",
    "living": "活体", "awakened": "觉醒", "adaptive": "自适应", "unstable": "不稳定",
    "superior": "高级", "holy": "神圣", "wise": "睿智", "strong": "强壮",
    "young_dragon": "幼龙", "promising": "有前途的", "suspicious": "可疑",
    "ugly": "丑陋", "very": "非常", "official": "官方", "sharpened": "磨利",
    "shrivelled": "干枯", "shrivelled_": "干枯", "wounded": "受伤", "rotten": "腐烂",
    "slimy": "黏滑", "soulflow": "灵魂流", "soultwist": "灵魂扭曲",
    "reality": "现实", "magical": "魔法", "elemental": "元素", "fading": "消退",
    "glistening": "闪光", "steaming-hot": "滚烫", "razor-sharp": "锐利",
    "super_sharp": "超锋利", "neon": "霓虹", "pastel": "柔彩", "cosmic": "宇宙",
    "polar": "极地", "highland": "高地", "lunar": "月华", "zodiac": "生肖",
    "midnight": "午夜", "starlight": "星光", "abyssal": "深渊", "azure": "蔚蓝",
    "teal": "青绿", "crimson": "绯红", "amber": "琥珀", "jade": "翡翠",
    "sapphire": "蓝宝石", "citrine": "黄水晶", "peridot": "橄榄石",
    "jasper": "碧玉", "topaz": "黄玉", "opal": "欧泊", "onyx": "缟玛瑙",
    "ruby": "红宝石", "aquamarine": "海蓝宝石", "amethyst": "紫水晶",
    # 颜色
    "black": "黑", "white": "白", "red": "红", "orange": "橙", "yellow": "黄",
    "lime": "黄绿", "green": "绿", "aqua": "水蓝", "cyan": "青", "blue": "蓝",
    "purple": "紫", "magenta": "品红", "pink": "粉", "brown": "棕", "gray": "灰",
    "grey": "灰", "silver": "银", "gold": "金", "golden": "黄金", "copper": "铜",
    "iron": "铁", "wooden": "木制", "dark": "暗", "light": "浅",
    "dark_blue": "深蓝", "light_blue": "浅蓝", "baby_blue": "淡蓝",
    "blue_gold": "蓝金", "rainbow": "彩虹", "multi": "多彩",
    # 生物 / 怪物
    "zombie": "僵尸", "skeleton": "骷髅", "spider": "蜘蛛", "creeper": "苦力怕",
    "enderman": "末影人", "endermite": "末影螨", "blaze": "烈焰人",
    "slime": "史莱姆", "magma": "岩浆怪", "ghast": "恶魂", "wolf": "狼",
    "cat": "猫", "ocelot": "豹猫", "cow": "牛", "mooshroom": "哞菇",
    "pig": "猪", "pigman": "猪人", "sheep": "羊", "chicken": "鸡",
    "rabbit": "兔子", "horse": "马", "bat": "蝙蝠", "squid": "鱿鱼",
    "fish": "鱼", "frog": "青蛙", "bee": "蜜蜂", "elephant": "大象",
    "tiger": "老虎", "lion": "狮子", "dragon": "龙", "wither": "凋灵",
    "warden": "监守者", "golem": "傀儡", "yeti": "雪怪", "rat": "老鼠",
    "mouse": "老鼠", "shark": "鲨鱼", "whale": "鲸鱼", "dolphin": "海豚",
    "turtle": "海龟", "seal": "海豹", "penguin": "企鹅", "owl": "猫头鹰",
    "eagle": "鹰", "parrot": "鹦鹉", "crow": "乌鸦", "giraffe": "长颈鹿",
    "monkey": "猴子", "goat": "山羊", "reindeer": "驯鹿", "turkey": "火鸡",
    "moth": "蛾", "mantis": "螳螂", "snail": "蜗牛", "slug": "蛞蝓",
    "worm": "蠕虫", "snake": "蛇", "hydra": "九头蛇", "mummy": "木乃伊",
    "skeletor": "骷髅怪", "ghoul": "食尸鬼", "wraith": "幽灵",
    "spectre": "幽魂", "spirit": "之灵", "phantom": "幻影",
    "guardian": "守卫者", "silverfish": "蠹虫", "armadillo": "犰狳",
    "ankylosaurus": "甲龙", "t-rex": "霸王龙", "mammoth": "猛犸象",
    "jellyfish": "水母", "flounder": "比目鱼", "salmon": "鲑鱼",
    "angler": "鮟鱇鱼", "squash": "南瓜", "scarecrow": "稻草人",
    "pest": "害虫", "vampire": "吸血鬼", "revenant": "亡魂", "tarantula": "狼蛛",
    "voidling": "虚空生灵", "endermite_": "末影螨", "sea_horse": "海马",
    # 词缀 / 装备系列
    "diamond": "钻石", "emerald": "绿宝石", "mithril": "秘银", "glacite": "冰川岩",
    "titanium": "钛", "sulphur": "硫磺", "crystal": "水晶", "quartz": "石英",
    "redstone": "红石", "lapis": "青金石", "obsidian": "黑曜石",
    "cobblestone": "圆石", "andesite": "安山岩", "sandstone": "砂岩",
    "gravel": "砂砾", "sand": "沙子", "clay": "黏土", "glowstone": "萤石",
    "netherrack": "下界岩", "oak": "橡木", "birch": "白桦", "spruce": "云杉",
    "acacia": "金合欢", "jungle": "丛林", "cocoa": "可可", "wart": "疣",
    "nether_wart": "下界疣", "cactus": "仙人掌", "sugar_cane": "甘蔗",
    "carrot": "胡萝卜", "potato": "马铃薯", "wheat": "小麦", "pumpkin": "南瓜",
    "melon": "西瓜", "mushroom": "蘑菇", "veilshroom": "纱菇", "berry": "浆果",
    "tulip": "郁金香", "rose": "玫瑰", "sunflower": "向日葵", "flower": "花",
    "sapling": "树苗", "tree": "树", "canopy": "树冠", "forest": "森林",
    "thornleaf": "棘叶", "creambloom": "奶油花", "fig": "无花果",
    # 装备部位 / 附件
    "sea_creature": "海怪", "miniboss": "小首领", "monster": "怪物",
    "boss": "首领", "npc": "NPC", "rift": "裂隙", "travel": "传送",
    "scroll": "卷轴", "portal": "传送门", "splash": "喷溅", "potion": "药水",
    "enrichment": "增益", "swapper": "切换器", "upgrade": "升级",
    "collateral": "抵押", "booster": "增强", "exp": "经验", "ultimate": "终极",
    "accessory": "饰品", "talisman": "护符", "ring": "戒指", "artifact": "神器",
    # 活动 / 地点
    "campfire": "篝火", "celebration": "庆典", "carnival": "嘉年华",
    "spook": "惊悚", "spooky": "阴森", "winter": "冬季", "summer": "夏季",
    "bingo": "宾果", "jerry": "杰瑞", "fairy": "仙灵", "dwarven": "矮人",
    "goblin": "哥布林", "kuudra": "库德拉", "torrhus": "托尔胡斯",
    "loch": "湖泊", "murkwater": "浊水", "howling": "嚎叫", "smoldering": "闷烧",
    "sepulture": "墓穴", "safari": "野生动物园", "den": "巢穴", "nest": "巢",
    "trapper": "捕兽人", "critter": "小动物", "jungle_island": "丛林岛",
    "barn": "谷仓", "lawn": "草坪", "patio": "庭院", "greenhouse": "温室",
    "wetlands": "湿地", "cave": "洞穴", "gorge": "峡谷", "garden": "花园",
    # 属性 / 效果
    "health": "生命值", "defense": "防御", "strength": "力量",
    "speed": "速度", "mana": "法力", "crit": "暴击", "archery": "弓术",
    "agility": "敏捷", "stamina": "耐力", "haste": "急速", "dodge": "闪避",
    "resistance": "抗性", "adrenaline": "肾上腺素", "healing": "治疗",
    "poison": "毒素", "blindness": "失明", "stun": "眩晕", "leech": "汲取",
    "bite": "撕咬", "crush": "碾压", "burst": "爆裂", "zap": "电击",
    "flare": "耀斑", "leap": "跳跃", "wake": "尾流", "gush": "喷涌",
    "timecharm": "时光护符", "fossil": "化石", "relic": "遗物",
    # 其它常见
    "pet": "宠物", "chick": "小鸡", "egg": "蛋", "meat": "肉",
    "bread": "面包", "beans": "豆子", "cheese": "奶酪", "paint": "颜料",
    "hope": "希望", "love": "爱", "star": "星辰", "heaven": "天堂",
    "riddle": "谜题", "secret": "秘密", "guide": "指南", "beginner": "新手",
    "music": "音乐", "vinyl": "唱片", "synthesizer": "合成器",
    "researcher": "研究员", "residents": "居民", "resident": "居民",
    "voter": "选民", "salvage": "回收", "deletor": "删除器", "dicer": "切块机",
    "chopper": "砍伐器", "cutter": "切割机", "synthesizer_": "合成器",
    "biker": "骑手", "hunter": "猎人", "pesthunter": "害虫猎人",
    "fisher": "渔夫", "miner": "矿工", "blacksmith": "铁匠", "wizard": "巫师",
    "knight": "骑士", "squire": "侍从", "paladin": "圣骑士", "lord": "领主",
    "king": "国王", "queen": "女王", "commander": "指挥官", "soldier": "士兵",
    "adventurer": "冒险者", "protester": "抗议者", "initiate": "见习",
    "cultist": "信徒", "scion": "后裔", "adept": "熟练者", "rookie": "新手",
    "expert": "专家", "veteran": "老兵", "grass": "草", "crop": "作物",
    "powder": "粉末", "sinker_": "沉子", "person": "人", "animal": "动物",
    "biome": "生物群系", "chip": "芯片", "mixin": "混剂", "crux": "核心",
    "treat": "零食", "gift": "礼物", "gifts": "礼物", "wish": "愿望",
    "orb": "宝珠", "vial": "瓶", "ampoule": "安瓿", "flask": "烧瓶",
    "talaria": "飞翼", "reaper": "死神", "grim": "冷酷", "shard": "碎片",
    "gel": "凝胶", "shuriken": "手里剑", "kunai": "苦无", "bokken": "木刀",
    "tactician": "战术家", "strategist": "战略家", "duelist": "决斗者",
}

# 整条替换：语序不是简单堆叠的、或需要官方译名
SPECIAL = {
    "Shadows indicate riddles, illuminating unseen stars": "阴影指向谜题，照亮未见之星",
    "A Beginner's Guide to Pesthunting": "害虫狩猎新手指南",
    "Slice of Strawberry Shortcake": "一角草莓蛋糕",
    "Very Official Yellow Rock of Love!": "非常官方的黄色爱心石！",
    "See, Hear, Speak Monkey Skin": "不看、不听、不说 猴子皮肤",
    "Super Jacob Entertainment System": "超级雅各布娱乐系统",
    "Trash Gobbler (Sea Creature)": "垃圾吞噬者（海怪）",
    "Tadgang (Frog) (Sea Creature)": "蝌蚪帮（青蛙）（海怪）",
}

# 括号里的分类标签
TAGS = {
    "npc": "NPC", "rift npc": "裂隙NPC", "monster": "怪物", "boss": "首领",
    "miniboss": "小首领", "sea creature": "海怪", "mythological creature": "神话生物",
    "rift": "裂隙", "frog": "青蛙",
}

# "X of Y" 这类要调换语序
OF_HEADS = {
    "crab hat": "螃蟹帽", "hat": "帽子", "rock": "石",
}


def _translate_words(text):
    out = []
    for w in text.split():
        core = w.strip(",.!")
        punct = w[len(core):]
        low = core.lower()
        cn = TYPES.get(low) or WORDS.get(low)
        out.append((cn + punct) if cn else w)
    return " ".join(out)


def translate(name):
    if name in SPECIAL:
        return SPECIAL[name]
    # 拆出末尾的括号标签：(Sea Creature) / (NPC) …
    tail = ""
    m = __import__("re").search(r"(\s*\([^()]*(?:\([^()]*\))?[^()]*\))+$", name)
    if m:
        tail_raw = m.group()
        tail = tail_raw
        name = name[:m.start()].strip()
        for eng, cn in TAGS.items():
            tail = tail.replace("(" + eng.title() + ")", "（" + cn + "）")
            tail = tail.replace("(" + eng.upper() + ")", "（" + cn + "）")
            tail = tail.replace("(" + eng + ")", "（" + cn + "）")
    # "XXX Hat of Celebration" → "庆典 XXX 帽"
    ce = __import__("re").match(r"(.+) Hat of Celebration( - .+)?$", name)
    if ce:
        body = _translate_words(ce.group(1))
        suffix = ce.group(2) or ""
        return f"{body}庆典帽{suffix} {tail}".strip()
    return (_translate_words(name) + " " + tail).strip()
