"""
测试反馈（feedback）的脚本：
- 自动读取 test_data/features/ 下的 NPZ 文件
- 多轮推荐 + 模拟反馈，观察推荐顺序的变化
"""

import os
import sys
from pathlib import Path
import numpy as np

# 准备路径
PROJECT_ROOT = Path(__file__).resolve().parent.parent.parent
SRC_PATH = PROJECT_ROOT / "src"
FEATURE_DIR = PROJECT_ROOT / "test_data" / "features"
OUTPUT_DIR = PROJECT_ROOT / "test_output"
PARAM_PATH = OUTPUT_DIR / "recommender_feedback_params.npz"

if str(SRC_PATH) not in sys.path:
    sys.path.insert(0, str(SRC_PATH))

from python_interface.recommender import Recommender
from python_interface.utils import create_playlist_from_npz_files


def load_playlist():
    """从 features 目录加载所有 NPZ，创建播放列表。"""
    npz_files = sorted(FEATURE_DIR.glob("*.npz"))
    if not npz_files:
        print(f"❌ 未在 {FEATURE_DIR} 找到任何 NPZ 文件。")
        print("请先运行特征提取脚本，或将 NPZ 文件放入该目录。")
        return []

    print(f"📥 加载 {len(npz_files)} 个 NPZ 文件用于测试反馈：")
    for npz_file in npz_files:
        print(f"  - {npz_file.name}")

    playlist = create_playlist_from_npz_files([str(p) for p in npz_files])
    return playlist


def simulate_feedback(item_id: str) -> float:
    """
    模拟用户反馈：
    - 用户一次只听一首歌
    - 随机给出 reward：0 / 0.5 / 1.0
    """
    return float(np.random.choice([0.0, 0.5, 1.0]))


def run_feedback_test(rounds: int = 15):
    """多轮推荐 + 反馈测试（一次一首歌）。"""
    playlist = load_playlist()
    if not playlist:
        return

    os.makedirs(OUTPUT_DIR, exist_ok=True)

    recommender = Recommender(
        storage=str(PARAM_PATH),
        playlist=playlist,
        initialization=True  # 每次测试都重新初始化
    )

    print("\n==============================")
    print("开始反馈测试")
    print("==============================")

    for round_idx in range(1, rounds + 1):
        print(f"\n🔁 第 {round_idx} 首推荐")
        item = recommender.selection(n=1)[0]
        reward = simulate_feedback(str(item.id))
        print(f" ▶︎ 推荐: {item.id} -> reward={reward:.1f}")
        recommender.feedback(item, reward)

    print("\n✅ 测试完成，你可以多运行几次观察推荐顺序变化。")
    print(f"ℹ️ 模型参数保存在: {PARAM_PATH}")


if __name__ == "__main__":
    run_feedback_test(rounds=20)

