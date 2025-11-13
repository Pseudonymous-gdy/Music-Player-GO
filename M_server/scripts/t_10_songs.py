"""
测试10首音乐的推荐系统
支持两种方式：
1. 从音频文件直接提取特征并推荐
2. 先提取特征到NPZ，再从NPZ文件推荐
"""

import os
import sys
from pathlib import Path
from glob import glob

# 添加项目路径
project_root = Path(__file__).parent.parent.parent
sys.path.insert(0, str(project_root / "src"))

from python_interface.recommender import Recommender
from python_interface.utils import (
    create_playlist_from_audio_files,
    create_playlist_from_npz_files
)
from python_interface.service.file_service.audio_features_fixed import make_fixed_vector, save_npz


def find_audio_files(audio_dir):
    """查找音频目录中的所有音频文件"""
    audio_extensions = ['*.mp3', '*.flac', '*.wav', '*.m4a', '*.ogg']
    audio_files = []
    for ext in audio_extensions:
        audio_files.extend(glob(os.path.join(audio_dir, ext)))
        audio_files.extend(glob(os.path.join(audio_dir, ext.upper())))
    return sorted(audio_files)


def test_method1_audio_direct(audio_files, n_recommend=5):
    """
    测试方式1: 直接从音频文件提取特征并推荐
    """
    print("\n" + "=" * 70)
    print("【测试方式1】从音频文件直接提取特征并推荐")
    print("=" * 70)
    
    if not audio_files:
        print("❌ 未找到音频文件")
        return None
    
    print(f"\n📁 找到 {len(audio_files)} 个音频文件:")
    for i, f in enumerate(audio_files, 1):
        print(f"  {i}. {os.path.basename(f)}")
    
    # 步骤1: 从音频文件创建播放列表（自动提取特征）
    print("\n📥 步骤1: 从音频文件提取特征...")
    playlist = create_playlist_from_audio_files(
        audio_files,
        feature="logmel",
        n_mels=128,
        pool="meanstd"
    )
    
    if not playlist:
        print("❌ 没有成功处理任何音频文件")
        return None
    
    print(f"\n✅ 成功处理 {len(playlist)} 首音乐")
    for item in playlist:
        print(f"  - {item.id}: 特征维度 {item.features.shape[0]}")
    
    # 步骤2: 创建推荐器
    print(f"\n🔧 步骤2: 初始化推荐器...")
    recommender = Recommender(
        storage="test_output/recommender_params_method1.npz",
        playlist=playlist,
        initialization=True
    )
    
    # 步骤3: 获取推荐
    print(f"\n🎵 步骤3: 获取推荐（推荐 {n_recommend} 首）...")
    recommended = recommender.selection(policy="LinUCB", n=n_recommend)
    
    # 步骤4: 输出结果
    print("\n" + "=" * 70)
    print("推荐结果:")
    print("=" * 70)
    for i, item in enumerate(recommended, 1):
        print(f"{i}. {item.name or item.id}")
        print(f"   ID: {item.id}")
        print(f"   特征维度: {item.features.shape[0]}")
    
    return recommended


def extract_features_to_npz(audio_files, output_dir):
    """
    批量提取特征到NPZ文件
    """
    print("\n" + "=" * 70)
    print("提取特征到NPZ文件")
    print("=" * 70)
    
    os.makedirs(output_dir, exist_ok=True)
    
    success_count = 0
    for audio_file in audio_files:
        try:
            # 提取特征
            features, meta = make_fixed_vector(
                audio_file,
                feature="logmel",
                n_mels=128,
                pool="meanstd"
            )
            
            # 保存为NPZ
            base_name = Path(audio_file).stem
            npz_path = os.path.join(output_dir, f"{base_name}.npz")
            save_npz(npz_path, features, meta)
            
            print(f"  ✅ {base_name}.npz (特征维度: {features.shape[0]})")
            success_count += 1
            
        except Exception as e:
            print(f"  ❌ 处理 {os.path.basename(audio_file)} 失败: {e}")
    
    print(f"\n✅ 成功提取 {success_count}/{len(audio_files)} 个文件")
    return success_count


def test_method2_npz_files(npz_dir, n_recommend=5):
    """
    测试方式2: 从NPZ文件加载并推荐
    """
    print("\n" + "=" * 70)
    print("【测试方式2】从NPZ文件加载并推荐")
    print("=" * 70)
    
    # 查找所有NPZ文件
    npz_files = glob(os.path.join(npz_dir, "*.npz"))
    
    if not npz_files:
        print("❌ 未找到NPZ文件")
        return None
    
    print(f"\n📁 找到 {len(npz_files)} 个NPZ文件:")
    for i, f in enumerate(npz_files, 1):
        print(f"  {i}. {os.path.basename(f)}")
    
    # 步骤1: 从NPZ文件创建播放列表
    print("\n📥 步骤1: 从NPZ文件加载特征...")
    playlist = create_playlist_from_npz_files(npz_files)
    
    if not playlist:
        print("❌ 没有成功加载任何NPZ文件")
        return None
    
    print(f"\n✅ 成功加载 {len(playlist)} 首音乐")
    for item in playlist:
        print(f"  - {item.id}: 特征维度 {item.features.shape[0]}")
    
    # 步骤2: 创建推荐器
    print(f"\n🔧 步骤2: 初始化推荐器...")
    recommender = Recommender(
        storage="test_output/recommender_params_method2.npz",
        playlist=playlist,
        initialization=True
    )
    
    # 步骤3: 获取推荐
    print(f"\n🎵 步骤3: 获取推荐（推荐 {n_recommend} 首）...")
    recommended = recommender.selection(policy="LinUCB", n=n_recommend)
    
    # 步骤4: 输出结果
    print("\n" + "=" * 70)
    print("推荐结果:")
    print("=" * 70)
    for i, item in enumerate(recommended, 1):
        print(f"{i}. {item.name or item.id}")
        print(f"   ID: {item.id}")
        print(f"   特征维度: {item.features.shape[0]}")
    
    return recommended


def main():
    """主测试函数"""
    print("=" * 70)
    print("音乐推荐系统测试")
    print("=" * 70)
    
    # 设置路径
    project_root = Path(__file__).parent.parent.parent
    audio_dir = project_root / "test_data" / "audio"
    features_dir = project_root / "test_data" / "features"
    output_dir = project_root / "test_output"
    
    # 创建目录
    os.makedirs(audio_dir, exist_ok=True)
    os.makedirs(features_dir, exist_ok=True)
    os.makedirs(output_dir, exist_ok=True)
    
    # 查找音频文件
    audio_files = find_audio_files(str(audio_dir))
    
    # 步骤1: 提取特征到NPZ（如果还没有）
    npz_files = glob(os.path.join(str(features_dir), "*.npz"))
    if not npz_files and audio_files:
        print("\n提取特征到NPZ...")
        extract_features_to_npz(audio_files, str(features_dir))
        npz_files = glob(os.path.join(str(features_dir), "*.npz"))
    
    # 步骤2: 从NPZ文件推荐
    if npz_files:
        test_method2_npz_files(str(features_dir), n_recommend=5)
    else:
        print(f"\n❌ 未找到NPZ文件！")
        print(f"请将NPZ文件放到: {features_dir}")
        print(f"或者将音频文件放到: {audio_dir} 让脚本自动提取特征")


if __name__ == "__main__":
    main()


