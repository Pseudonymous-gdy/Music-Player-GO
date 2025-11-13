"""
从音频文件提取特征 -> 输入推荐算法 -> 获取输出
测试脚本
"""

import os
import sys
from pathlib import Path

# 添加项目路径
project_root = Path(__file__).parent.parent.parent
sys.path.insert(0, str(project_root / "src"))

from python_interface.recommender import Recommender
from python_interface.utils import (
    create_playlist_from_audio_files,
    create_playlist_from_npz_files
)

def process_audio_to_recommendation(audio_files, n_recommend=3, storage_path="recommender_params.npz"):
    """
    从音频文件到推荐的完整流程
    
    Args:
        audio_files: 音频文件路径列表
        n_recommend: 推荐数量
        storage_path: 推荐器参数存储路径
    """
    print("=" * 60)
    print("从音频文件到推荐 - 完整流程")
    print("=" * 60)
    
    # 步骤1: 从音频文件提取特征并创建MusicItem
    print("\n📥 步骤1: 从音频文件提取特征...")
    
    # 过滤存在的文件
    existing_files = [f for f in audio_files if os.path.exists(f)]
    if not existing_files:
        print("❌ 没有找到任何音频文件")
        return None
    
    playlist = create_playlist_from_audio_files(
        existing_files,
        feature="logmel",
        n_mels=128,
        pool="meanstd"
    )
    
    for item in playlist:
        print(f"  ✅ {item.id}: 特征维度 {item.features.shape[0]}")
    
    if not playlist:
        print("❌ 没有成功处理任何音频文件")
        return None
    
    # 步骤2: 创建推荐器
    print(f"\n🔧 步骤2: 初始化推荐器（共 {len(playlist)} 首音乐）...")
    recommender = Recommender(
        storage=storage_path,
        playlist=playlist,
        initialization=True
    )
    
    # 步骤3: 获取推荐
    print(f"\n🎵 步骤3: 获取推荐（推荐 {n_recommend} 首）...")
    recommended = recommender.selection(policy="LinUCB", n=n_recommend)
    
    # 步骤4: 输出结果
    print("\n" + "=" * 60)
    print("推荐结果:")
    print("=" * 60)
    for i, item in enumerate(recommended, 1):
        print(f"{i}. ID: {item.id}")
        print(f"   名称: {item.name}")
        print(f"   特征维度: {item.features.shape[0]}")
    
    return recommended

def process_npz_to_recommendation(npz_files, n_recommend=3, storage_path="recommender_params.npz"):
    """
    从NPZ文件到推荐的流程
    
    Args:
        npz_files: NPZ文件路径列表
        n_recommend: 推荐数量
        storage_path: 推荐器参数存储路径
    """
    print("=" * 60)
    print("从NPZ文件到推荐 - 完整流程")
    print("=" * 60)
    
    # 步骤1: 从NPZ文件加载特征并创建MusicItem
    print("\n📥 步骤1: 从NPZ文件加载特征...")
    
    # 过滤存在的文件
    existing_files = [f for f in npz_files if os.path.exists(f)]
    if not existing_files:
        print("❌ 没有找到任何NPZ文件")
        return None
    
    playlist = create_playlist_from_npz_files(existing_files)
    
    for item in playlist:
        print(f"  ✅ {item.id}: 特征维度 {item.features.shape[0]}")
    
    if not playlist:
        print("❌ 没有成功加载任何NPZ文件")
        return None
    
    # 步骤2: 创建推荐器
    print(f"\n🔧 步骤2: 初始化推荐器（共 {len(playlist)} 首音乐）...")
    recommender = Recommender(
        storage=storage_path,
        playlist=playlist,
        initialization=True
    )
    
    # 步骤3: 获取推荐
    print(f"\n🎵 步骤3: 获取推荐（推荐 {n_recommend} 首）...")
    recommended = recommender.selection(policy="LinUCB", n=n_recommend)
    
    # 步骤4: 输出结果
    print("\n" + "=" * 60)
    print("推荐结果:")
    print("=" * 60)
    for i, item in enumerate(recommended, 1):
        print(f"{i}. ID: {item.id}")
        print(f"   名称: {item.name}")
        print(f"   特征维度: {item.features.shape[0]}")
    
    return recommended

if __name__ == "__main__":
    # 测试1: 从音频文件
    print("\n【测试1】从音频文件提取特征并推荐")
    audio_files = [
        "../../service/file_service/music/漂移.mp3",
        "../../service/file_service/music/隐形的翅膀.flac"
    ]
    
    # 检查文件是否存在
    existing_audio = [f for f in audio_files if os.path.exists(f)]
    if existing_audio:
        process_audio_to_recommendation(existing_audio, n_recommend=2)
    else:
        print("⚠️  未找到音频文件，跳过测试1")
    
    # 测试2: 从NPZ文件
    print("\n\n【测试2】从NPZ文件加载并推荐")
    npz_files = [
        "../../service/file_service/features_out/漂移.npz",
        "../../service/file_service/features_out/隐形的翅膀.npz"
    ]
    
    # 检查文件是否存在
    existing_npz = [f for f in npz_files if os.path.exists(f)]
    if existing_npz:
        process_npz_to_recommendation(existing_npz, n_recommend=2)
    else:
        print("⚠️  未找到NPZ文件，跳过测试2")

