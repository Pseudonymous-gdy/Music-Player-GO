"""
工具函数：用于处理音频特征和推荐系统的辅助函数
"""

import sys
import os
from pathlib import Path
import numpy as np
import json
from typing import List, Optional

# 添加src路径以便导入utils
project_root = Path(__file__).parent.parent.parent
src_path = project_root / "src"
if str(src_path) not in sys.path:
    sys.path.insert(0, str(src_path))

from .recommender import MusicItem

# 直接实现load_npz，避免导入问题
def load_npz(file_path):
    """加载NPZ文件，返回特征和元数据"""
    data = np.load(file_path, allow_pickle=False)
    features = data['x']
    
    if 'meta' in data.files:
        meta_bytes = data['meta']

        # 兼容不同的保存格式
        if isinstance(meta_bytes, np.ndarray):
            # dtype=object: 可能是单个bytes对象
            if meta_bytes.dtype == object:
                meta_bytes = meta_bytes.item()
            # dtype=uint8: 以uint8数组形式保存
            elif meta_bytes.dtype == np.uint8:
                meta_bytes = meta_bytes.tobytes()
            else:
                meta_bytes = meta_bytes.tobytes()

        if isinstance(meta_bytes, bytes):
            meta = json.loads(meta_bytes.decode('utf-8'))
        else:
            # 如果仍然不是bytes，直接尝试json解析
            meta = json.loads(meta_bytes)
    else:
        meta = None
    
    return features, meta

from .service.file_service.audio_features_fixed import make_fixed_vector


def create_music_item_from_npz(npz_path: str, music_id: Optional[str] = None, name: Optional[str] = None) -> MusicItem:
    """
    从NPZ文件创建MusicItem（NPZ格式：{'x': features, 'meta': meta_bytes}）
    
    Args:
        npz_path: NPZ文件路径
        music_id: 音乐ID，如果不提供则使用文件名
        name: 音乐名称，如果不提供则使用ID
        
    Returns:
        MusicItem对象
    """
    features, meta = load_npz(npz_path)
    
    if music_id is None:
        music_id = Path(npz_path).stem
    
    if name is None:
        name = music_id
    
    item = MusicItem(
        id=music_id,
        features=features,
        name=name
    )
    
    return item


def create_music_item_from_audio(audio_path: str, music_id: Optional[str] = None, 
                                 name: Optional[str] = None,
                                 feature: str = "logmel", 
                                 n_mels: int = 128,
                                 n_mfcc: int = 13,
                                 pool: str = "meanstd") -> MusicItem:
    """
    从音频文件创建MusicItem（提取特征）
    
    Args:
        audio_path: 音频文件路径
        music_id: 音乐ID，如果不提供则使用文件名
        name: 音乐名称，如果不提供则使用ID
        feature: 特征类型 ("logmel" 或 "mfcc")
        n_mels: Mel频带数量
        n_mfcc: MFCC数量
        pool: 池化方法
        
    Returns:
        MusicItem对象
    """
    features, meta = make_fixed_vector(
        audio_path,
        feature=feature,
        n_mels=n_mels,
        n_mfcc=n_mfcc,
        pool=pool
    )
    
    if music_id is None:
        music_id = Path(audio_path).stem
    
    if name is None:
        name = music_id
    
    item = MusicItem(
        id=music_id,
        features=features,
        name=name
    )
    
    return item


def create_playlist_from_npz_files(npz_files: List[str]) -> List[MusicItem]:
    """
    从NPZ文件列表创建播放列表
    
    Args:
        npz_files: NPZ文件路径列表
        
    Returns:
        MusicItem列表
    """
    playlist = []
    for npz_file in npz_files:
        try:
            item = create_music_item_from_npz(npz_file)
            playlist.append(item)
        except Exception as e:
            print(f"❌ 加载 {npz_file} 失败: {e}")
    return playlist


def create_playlist_from_audio_files(audio_files: List[str],
                                     feature: str = "logmel",
                                     n_mels: int = 128,
                                     n_mfcc: int = 13,
                                     pool: str = "meanstd") -> List[MusicItem]:
    """
    从音频文件列表创建播放列表
    
    Args:
        audio_files: 音频文件路径列表
        feature: 特征类型
        n_mels: Mel频带数量
        n_mfcc: MFCC数量
        pool: 池化方法
        
    Returns:
        MusicItem列表
    """
    playlist = []
    for audio_file in audio_files:
        try:
            item = create_music_item_from_audio(
                audio_file,
                feature=feature,
                n_mels=n_mels,
                n_mfcc=n_mfcc,
                pool=pool
            )
            playlist.append(item)
        except Exception as e:
            print(f"❌ 处理 {audio_file} 失败: {e}")
    return playlist

