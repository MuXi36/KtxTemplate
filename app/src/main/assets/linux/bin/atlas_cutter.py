#!/usr/bin/env python3
"""
UIPackedAtlas 图集切割工具
从 KTX 图集+Lua坐标元数据 → 切割出单个UI元素PNG，按前缀自动分类

用法:
  python3 atlas_cutter.py                           # 切割所有图集
  python3 atlas_cutter.py UIPackedAtlas1            # 只切割指定图集
  python3 atlas_cutter.py --dry-run                 # 预览，不实际切割
  python3 atlas_cutter.py --classify-only           # 只分类(不重新切割)
"""

import re
import os
import sys
import struct
import importlib.util
from collections import defaultdict
from pathlib import Path

# ── 导入转换模块 ──
spec = importlib.util.spec_from_file_location('converter', '/sdcard/MT2/mcp/转换_v2.py')
converter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(converter)

from PIL import Image
import numpy as np

# ── 配置 ──
KTX_DIR = '/sdcard/MT2/apks/sky'
JSON_DIR = '/sdcard/MT2/mcp/json'
OUTPUT_BASE = '/sdcard/MT2/mcp/sliced_ui'

# Lua元数据文件
ATLAS_LUA_FILES = {
    'UIPackedAtlas.lua': 'UIPackedAtlas',
    'UIMapAtlas.lua': 'UiMapAtlas',
    'UiColorAtlas.lua': 'UiColorAtlas',
}

# ── 分类规则 ──
def classify(name):
    """根据名称前缀返回分类目录（光遇中文术语）"""
    rules = [
        ('UiEmote', '表情'),
        ('UiButton', '按键图标'),
        ('UiMisc', '杂项图标'),
        ('UiMap', '地图'),
        ('UiAnalog', '摇杆'),
        ('UiBorder', '边框'),
        ('UiBrand', '品牌'),
        ('UiCrab', '螃蟹'),
        ('UiDisplay', '显示'),
        ('UiMenu', '菜单'),
        ('UiHud', '界面'),
        ('UiOutfitBody', '装扮/裤子'),
        ('UiOutfitCape', '装扮/斗篷'),
        ('UiOutfitHair', '装扮/发型'),
        ('UiOutfitHorn', '装扮/头角'),
        ('UiOutfitMask', '装扮/面具'),
        ('UiOutfitProp', '装扮/道具'),
        ('UiOutfitFeet', '装扮/鞋子'),
        ('UiOutfitFace', '装扮/面部'),
        ('UiOutfitNeck', '装扮/颈饰'),
        ('UiOutfitHat', '装扮/头饰'),
        ('UiOutfit', '装扮/其他'),
        ('UiSocial', '社交'),
        ('UiSpirit', '先祖'),
        ('UiFriend', '好友'),
        ('UiGift', '礼物'),
        ('UiQuest', '任务'),
        ('UiSeason', '季节'),
        ('UiSettings', '设置'),
        ('UiSpell', '魔法'),
        ('UiWing', '光翼'),
        ('UiNPC', 'NPC'),
        ('UiMatch', '匹配'),
        ('UiToggle', '开关'),
        ('UiRadial', '径向渐变'),
        ('UiGradient', '渐变'),
        ('UiMusic', '音乐'),
        ('UiPlaceholder', '占位图'),
        ('UiWip', '开发中'),
    ]
    for prefix, folder in rules:
        if name.startswith(prefix):
            return folder
    return '其他'


# ── Lua解析 ──
def parse_atlas_lua(filepath):
    """解析 ImageRegion 定义，返回 [(name, atlas, u1, v1, u2, v2), ...]"""
    with open(filepath, 'r') as f:
        content = f.read()
    
    pattern = r'resource "ImageRegion" "([^"]+)" \{ image = "([^"]+)", uv = \{ ([^}]+) \} \}'
    matches = re.findall(pattern, content)
    
    regions = []
    for name, atlas, uv_str in matches:
        parts = [p.strip() for p in uv_str.split(',')]
        try:
            u1 = eval(parts[0])
            v1 = eval(parts[1])
            u2 = eval(parts[2])
            v2 = eval(parts[3])
            regions.append((name, atlas, u1, v1, u2, v2))
        except Exception as e:
            print(f"  [警告] 无法解析UV: {name}: {uv_str} - {e}")
    
    return regions


# ── 图集加载与解码 ──
def load_atlas(atlas_name):
    """加载KTX图集，返回 (width, height, rgba_pixels)"""
    ktx_path = os.path.join(KTX_DIR, f'{atlas_name}.ktx')
    if not os.path.exists(ktx_path):
        print(f"  [错误] KTX文件不存在: {ktx_path}")
        return None, None, None
    
    with open(ktx_path, 'rb') as f:
        ktx_data = f.read()
    
    try:
        w, h, pixels, fmt_name = converter.ktx_to_png(ktx_data)
        print(f"  加载: {atlas_name}.ktx → {fmt_name}, {w}×{h}")
        
        # 如果是R11_EAC灰度图集，应用模式2视觉增强
        if 'R11_EAC' in fmt_name:
            print(f"    应用视觉增强 (模式2)...")
            pixels = converter.enhance_grayscale(pixels, w, h)
        
        return w, h, pixels
    except Exception as e:
        print(f"  [错误] 解码失败: {e}")
        return None, None, None


# ── 切割核心 ──
def crop_region(pixels, atlas_w, atlas_h, u1, v1, u2, v2):
    """从图集像素中裁剪指定UV区域，返回 (cropped_pixels, w, h)"""
    x1 = int(round(u1 * atlas_w))
    y1 = int(round(v1 * atlas_h))
    x2 = int(round(u2 * atlas_w))
    y2 = int(round(v2 * atlas_h))
    
    # 边界保护
    x1 = max(0, min(x1, atlas_w))
    y1 = max(0, min(y1, atlas_h))
    x2 = max(0, min(x2, atlas_w))
    y2 = max(0, min(y2, atlas_h))
    
    w = x2 - x1
    h = y2 - y1
    
    if w <= 0 or h <= 0:
        return None, 0, 0
    
    arr = np.frombuffer(bytes(pixels), dtype=np.uint8).reshape(atlas_h, atlas_w, 4)
    crop = arr[y1:y2, x1:x2].copy()
    
    return bytearray(crop.tobytes()), w, h


# ── 主流程 ──
def main():
    dry_run = '--dry-run' in sys.argv
    classify_only = '--classify-only' in sys.argv
    target_atlas = None
    
    for arg in sys.argv[1:]:
        if not arg.startswith('--') and arg not in ('1', '2'):
            target_atlas = arg
    
    print("=" * 60)
    print("UIPackedAtlas 图集切割工具")
    print("=" * 60)
    
    # 1. 解析所有Lua元数据
    all_regions = []
    for lua_file, atlas_prefix in ATLAS_LUA_FILES.items():
        lua_path = os.path.join(JSON_DIR, lua_file)
        if not os.path.exists(lua_path):
            continue
        regions = parse_atlas_lua(lua_path)
        print(f"\n解析 {lua_file}: {len(regions)} 个区域")
        all_regions.extend(regions)
    
    print(f"\n总计: {len(all_regions)} 个UI元素")
    
    # 2. 按图集分组
    atlas_groups = defaultdict(list)
    for name, atlas, u1, v1, u2, v2 in all_regions:
        if target_atlas and atlas != target_atlas:
            continue
        atlas_groups[atlas].append((name, u1, v1, u2, v2))
    
    if target_atlas:
        print(f"筛选目标图集: {target_atlas}")
    print(f"待处理图集: {len(atlas_groups)} 个")
    
    # 3. 逐图集处理
    total_cropped = 0
    total_skipped = 0
    stats = defaultdict(lambda: {'count': 0, 'total_px': 0})
    
    for atlas_name in sorted(atlas_groups.keys()):
        regions = atlas_groups[atlas_name]
        print(f"\n{'─' * 50}")
        print(f"处理: {atlas_name} ({len(regions)} 个区域)")
        
        if dry_run:
            for name, u1, v1, u2, v2 in regions[:5]:
                print(f"  [预览] {name}: UV=({u1:.4f},{v1:.4f})-({u2:.4f},{v2:.4f})")
            if len(regions) > 5:
                print(f"  ... 还有 {len(regions)-5} 个")
            total_cropped += len(regions)
            continue
        
        if classify_only:
            # 只做分类统计
            for name, u1, v1, u2, v2 in regions:
                cat = classify(name)
                stats[cat]['count'] += 1
            continue
        
        # 加载图集
        atlas_w, atlas_h, pixels = load_atlas(atlas_name)
        if pixels is None:
            total_skipped += len(regions)
            continue
        
        # 切割每个区域
        for name, u1, v1, u2, v2 in regions:
            crop_data, cw, ch = crop_region(pixels, atlas_w, atlas_h, u1, v1, u2, v2)
            
            if crop_data is None:
                total_skipped += 1
                continue
            
            # 分类目录
            cat = classify(name)
            out_dir = os.path.join(OUTPUT_BASE, cat)
            os.makedirs(out_dir, exist_ok=True)
            
            # 保存PNG
            out_path = os.path.join(out_dir, f'{name}.png')
            img = Image.frombytes('RGBA', (cw, ch), bytes(crop_data))
            img.save(out_path, 'PNG')
            
            total_cropped += 1
            stats[cat]['count'] += 1
            stats[cat]['total_px'] += cw * ch
    
    # 4. 输出统计
    print(f"\n{'=' * 60}")
    print(f"完成!")
    print(f"  切割成功: {total_cropped}")
    print(f"  跳过: {total_skipped}")
    print(f"  输出目录: {OUTPUT_BASE}")
    
    if stats:
        print(f"\n分类统计:")
        for cat in sorted(stats.keys()):
            s = stats[cat]
            print(f"  {cat:<25} {s['count']:>5} 个  ({s['total_px']:>12,} px²)")
        print(f"  {'总计':<25} {total_cropped:>5} 个")


if __name__ == '__main__':
    main()
