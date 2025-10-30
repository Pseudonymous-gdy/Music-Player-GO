# test_linucb_with_theta_csv.py
# -*- coding: utf-8 -*-

import io
import numpy as np
import pandas as pd
import pytest

# 替换为你项目中的实际模块路径
from Recommender import Recommender, MusicItem

THETA_CSV = """ID, Feature 1, Feature 2, Feature 3, Feature 4, Feature 5
1, 0.1, 0.2, -0.3, 0.4, 0.15
2, 0.2, 0.3, -0.4, 0.5, 0.25
3, 0.3, 0.4, -0.5, 0.6, 0.35
4, 0.4, 0.5, -0.6, 0.7, 0.45
5, -0.9, 0.6, -0.7, 0.24, 0.05
6, 0.62, 0.72, -0.8, 0.5, 0.69
7, 0.59, 0.87, -0.9, 1.2, 0.71
8, 0.8, 0.95, -1.0, 0.3, 0.55
9, 0.9, 1.03, -1.12, 0.53, 0.25
10, -0.65, 1.12, -2.15, 0.68, 1.05
"""

@pytest.fixture
def theta_csv_path(tmp_path):
    p = tmp_path / "test.csv"
    p.write_text(THETA_CSV, encoding="utf-8")
    return p

def make_items(ids):
    return [MusicItem(i) for i in ids]

def test_linucb_with_theta_csv(theta_csv_path, tmp_path):
    # ---- 1) 构造 items，并把 θ_a 从 CSV 加载到 item.features ----
    ids = list(range(1, 11))
    items = make_items(ids)
    for it in items:
        it.load_theta_from_csv(str(theta_csv_path), id_col="ID")
        assert it.features is not None
        assert it.features.shape == (5,)

    d = 5
    # ---- 2) 构建 Recommender：alpha=0 方便确定性断言；设置先验强度 prior_lambda=4；l2=1 ----
    rec = Recommender(playlist=items, alpha=0.0, l2=1.0, prior_lambda=4.0)

    # ---- 3) 准备本轮上下文 x_{t,a}：全部取 e1=[1,0,0,0,0]，这样排序只看 θ[0] ----
    e1 = np.zeros(d, dtype=np.float64); e1[0] = 1.0
    contexts_by_id = {str(i): e1 for i in ids}

    # ---- 4) 选择 Top-3，并断言顺序（按第一列值降序：ID=9,8,6）----
    top3 = rec.selection(policy="LinUCB", n=3, contexts_by_id=contexts_by_id)
    top3_ids = [it.id for it in top3]
    assert top3_ids == [9, 8, 6]

    # ---- 5) 先验是否注入到 A,b？所有臂都在 selection 中被初始化过 ----
    # A_a = (l2 + prior_lambda) * I, b_a = prior_lambda * theta_a
    expected_diag = (1.0 + 4.0)  # 5.0
    for i in ids:
        A = rec._A[i]
        b = rec._b[i]
        assert A.shape == (d, d)
        assert b.shape == (d,)
        assert np.allclose(A, np.eye(d) * expected_diag)
        # 取 CSV 的 θ_a
        theta_i = next(it.features for it in items if it.id == i)
        assert np.allclose(b, 4.0 * theta_i)

    # ---- 6) 进行一次在线更新：对 ID=9 播放，reward=1.0，x=e1 ----
    item9 = next(it for it in items if it.id == 9)
    A_before = rec._A[9].copy()
    b_before = rec._b[9].copy()

    rec.observe(item9, reward=1.0, x_context=e1)

    A_after = rec._A[9]
    b_after = rec._b[9]
    # A 增加 e1 e1^T
    assert np.isclose(A_after[0, 0], A_before[0, 0] + 1.0)
    assert np.allclose(A_after[1:, 0], A_before[1:, 0])  # 其他列不变
    assert np.allclose(A_after[0, 1:], A_before[0, 1:])
    # b 增加 reward * e1
    expected_b = b_before.copy(); expected_b[0] += 1.0
    assert np.allclose(b_after, expected_b)

    # ---- 7) 持久化参数并恢复，检查一致性 ----
    state_path = tmp_path / "linucb_state.npz"
    rec.save_params(str(state_path))

    rec2 = Recommender(playlist=items, alpha=999.0, l2=999.0, prior_lambda=999.0)  # 将被覆盖
    rec2.load_params(str(state_path))

    # A,b,超参一致
    for i in ids:
        assert np.allclose(rec2._A[i], rec._A[i])
        assert np.allclose(rec2._b[i], rec._b[i])
    assert rec2.alpha == rec.alpha
    assert rec2.l2 == rec.l2
    assert rec2.prior_lambda == rec.prior_lambda

    # 同一上下文下选择结果一致
    top3_rec1 = [it.id for it in rec.selection(policy="LinUCB", n=3, contexts_by_id=contexts_by_id)]
    top3_rec2 = [it.id for it in rec2.selection(policy="LinUCB", n=3, contexts_by_id=contexts_by_id)]
    assert top3_rec1 == top3_rec2
