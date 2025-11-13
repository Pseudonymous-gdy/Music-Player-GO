import os
import json
import numpy as np

def load_npz(file_path):
    """
    加载NPZ文件，返回特征和元数据
    
    NPZ格式: {'x': features_array, 'meta': json_bytes}
    """
    data = np.load(file_path, allow_pickle=False)
    features = data['x']
    
    # meta是json编码的bytes，需要解码
    if 'meta' in data.files:
        meta_bytes = data['meta']
        if isinstance(meta_bytes, np.ndarray) and meta_bytes.dtype == object:
            meta = json.loads(meta_bytes.item().decode('utf-8'))
        elif isinstance(meta_bytes, bytes):
            meta = json.loads(meta_bytes.decode('utf-8'))
        else:
            # 尝试直接解码
            meta = json.loads(meta_bytes.decode('utf-8'))
    else:
        meta = None
    
    return features, meta

def save_npz(out_path, x, meta):
    """
    保存特征和元数据到NPZ文件
    
    Args:
        out_path: 输出路径
        x: 特征向量 (numpy array)
        meta: 元数据字典（会被编码为json bytes）
    """
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    meta_json = json.dumps(meta, ensure_ascii=False).encode("utf-8")
    np.savez_compressed(out_path, x=x, meta=meta_json)

def list_npz_files(directory):
    return [os.path.join(directory, f) for f in os.listdir(directory) if f.endswith('.npz')]

def validate_npz(file_path):
    try:
        features, meta = load_npz(file_path)
        return features is not None and meta is not None
    except Exception as e:
        print(f"Error loading {file_path}: {e}")
        return False

def get_feature_shape(file_path):
    features, _ = load_npz(file_path)
    return features.shape if features is not None else None