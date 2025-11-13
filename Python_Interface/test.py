# test_linucb_with_theta_csv.py
# -*- coding: utf-8 -*-

import os
import io
import numpy as np
import pandas as pd
import pytest
import torch

from Recommender import Recommender, MusicItem, RNN


# ============================================================
# 原有测试：MusicItem CSV, Recommender 基础 LinUCB 测试
# ============================================================

def test_music_item_load_x_csv():
    '''
    Test MusicItem.load_x() with CSV input.
    '''
    path = "Python_Interface/test.csv"
    music_items = [MusicItem(id=i, feature_dim=5) for i in range(1, 11)]
    for item in music_items:
        item.load_x(storage=path, id_col="ID")
        assert item.features.shape == (5,)


def test_recommender_loading():
    '''
    Test Initialization: whether Recommender loads parameters.
    '''
    ads = Recommender(playlist=[MusicItem(id=i, feature_dim=5) for i in range(1, 11)])
    for param in ['_A', '_b']:
        param_dict = getattr(ads, param)
        assert len(param_dict) == 10
        for i in range(1, 11):
            assert param_dict[i].shape == (5, 5) if param == '_A' else (5,)


def test_recommender_selection():
    '''
    Test Recommender Fundamental Selection & Update Logic in LinUCB mode.
    '''
    ads = Recommender(
        playlist=[MusicItem(id=i, feature_dim=5) for i in range(1, 11)],
        initialization=True
    )
    assert ads is not None

    # Assume reward calculation θ_a follows a simple gaussian distribution
    def get_real_theta():
        return np.random.normal(loc=0.3, scale=1.0, size=5)

    # simulate 5 rounds of selection and feedback
    for _ in range(5):
        selected_items = ads.selection(n=3)
        assert len(selected_items) == 3
        for item in selected_items:
            real_theta = get_real_theta()
            reward = float(np.dot(real_theta, item.features) + np.random.normal(0, 0.1))
            ads.feedback(item, reward)

    flag = False
    for param in ['_A', '_b']:
        param_dict = getattr(ads, param)
        for i in range(1, 11):
            if param == '_A' and not np.array_equal(param_dict[i], np.eye(5)):
                flag = True
            if param == '_b' and not np.array_equal(param_dict[i], np.zeros(5)):
                flag = True
    assert flag, "Parameters did not update after feedback."


# ============================================================
# 新增测试：MusicItem NPZ、RNN、LinUCB+ 逻辑
# ============================================================

def test_music_item_load_x_npz(tmp_path):
    """
    Test MusicItem.load_x() with NPZ input.
    """
    path = tmp_path / "theta.npz"
    data = {}
    for i in range(1, 6):
        data[str(i)] = np.random.randn(5).astype(np.float64)
    np.savez_compressed(path, **data)

    music_items = [MusicItem(id=i, feature_dim=5) for i in range(1, 6)]
    for item in music_items:
        item.load_x(storage=str(path), id_col="ID")  # id_col ignored for npz
        assert item.features.shape == (5,)
        np.testing.assert_allclose(item.features, data[str(item.id)])


def test_music_item_load_x_csv_missing_id_raises(tmp_path):
    """
    Test that MusicItem.load_x() raises ValueError when ID not present in CSV.
    """
    path = tmp_path / "theta.csv"
    df = pd.DataFrame({
        "ID": [1, 2, 3],
        "f1": [0.1, 0.2, 0.3],
        "f2": [0.4, 0.5, 0.6],
        "f3": [0.7, 0.8, 0.9],
        "f4": [1.0, 1.1, 1.2],
        "f5": [1.3, 1.4, 1.5],
    })
    df.to_csv(path, index=False)

    item_ok = MusicItem(id=1, feature_dim=5)
    item_ok.load_x(storage=str(path), id_col="ID")
    assert item_ok.features.shape == (5,)

    item_bad = MusicItem(id=10, feature_dim=5)
    with pytest.raises(ValueError):
        item_bad.load_x(storage=str(path), id_col="ID")


# -------------------------------
# RNN standalone tests
# -------------------------------

def test_rnn_forward_and_dim_check(tmp_path):
    """
    Test RNN.forward shapes and dimension mismatch error.
    """
    storage = tmp_path / "rnn_params.npz"
    dim = 4
    hidden_size = 6
    rnn = RNN(dim=dim, storage=str(storage), hidden_size=hidden_size)

    x = np.random.randn(dim).astype(np.float32)
    h, beta = rnn.forward(x)

    assert isinstance(h, torch.Tensor)
    assert isinstance(beta, torch.Tensor)
    assert h.shape == (hidden_size,)
    assert beta.shape == (dim,)

    # Wrong dimension should raise ValueError
    x_bad = np.random.randn(dim + 1).astype(np.float32)
    with pytest.raises(ValueError):
        rnn.forward(x_bad)


def test_rnn_hidden_state_updates_only_in_train_per_update(tmp_path):
    """
    Ensure that internal hidden state (h_t_1, X_t_1) is updated only in train_per_update,
    not in plain forward calls.
    """
    storage = tmp_path / "rnn_params2.npz"
    dim = 3
    hidden_size = 5
    rnn = RNN(dim=dim, storage=str(storage), hidden_size=hidden_size)

    x = np.random.randn(dim).astype(np.float32)

    # Before any training, internal state should be None
    assert rnn.h_t_1 is None
    assert rnn.X_t_1 is None

    # forward() should NOT modify internal state
    _h, _beta = rnn.forward(x, h0=None)
    assert rnn.h_t_1 is None
    assert rnn.X_t_1 is None

    # train_per_update should initialize and update internal state
    rnn.train_per_update(x, reward=1.0)
    assert rnn.h_t_1 is not None
    assert rnn.X_t_1 is not None

    prev_h = rnn.h_t_1.clone()
    rnn.train_per_update(x, reward=0.5)
    assert not torch.allclose(prev_h, rnn.h_t_1)


def test_rnn_save_and_load_roundtrip(tmp_path):
    """
    Test that RNN.save_model and load_model correctly restore parameters.
    """
    storage = tmp_path / "rnn_params3.npz"
    dim = 5
    hidden_size = 7
    rnn = RNN(dim=dim, storage=str(storage), hidden_size=hidden_size)

    # Save initial parameters
    rnn.save_model()
    assert storage.exists()

    # Clone parameters for comparison
    saved_params = [p.detach().clone() for p in rnn.parameters()]

    # Create new instance and load
    rnn2 = RNN(dim=dim, storage=str(storage), hidden_size=hidden_size)
    rnn2.load_model()
    loaded_params = [p.detach() for p in rnn2.parameters()]

    assert len(saved_params) == len(loaded_params)
    for p_saved, p_loaded in zip(saved_params, loaded_params):
        assert torch.allclose(p_saved, p_loaded)


def test_rnn_train_per_update_changes_parameters(tmp_path):
    """
    Test that train_per_update actually changes RNN parameters.
    """
    storage = tmp_path / "rnn_params4.npz"
    dim = 4
    hidden_size = 6
    rnn = RNN(dim=dim, storage=str(storage), hidden_size=hidden_size)

    x = np.random.randn(dim).astype(np.float32)

    before = torch.cat([p.detach().view(-1) for p in rnn.parameters()])
    for _ in range(5):
        rnn.train_per_update(x, reward=0.5)
    after = torch.cat([p.detach().view(-1) for p in rnn.parameters()])

    assert not torch.allclose(before, after), "RNN parameters did not change after train_per_update."


# -------------------------------
# Recommender LinUCB+ tests
# -------------------------------

def test_recommender_linucb_plus_basic_flow(tmp_path):
    """
    Test Recommender with LinUCB+:
    - RNN is attached
    - selection runs before and after feedback
    - hidden state is only updated via feedback
    """
    storage = tmp_path / "recommender_plus.npz"
    d = 5
    playlist = [MusicItem(id=i, feature_dim=d) for i in range(1, 6)]

    rec = Recommender(
        storage=str(storage),
        playlist=playlist,
        policy="LinUCB+",
        initialization=True,
        hidden_size=2 * d,
    )

    assert hasattr(rec, "rnn_model")
    assert isinstance(rec.rnn_model, RNN)

    # Before any feedback, selection should work and hidden state should be None
    items = rec.selection(n=2)
    assert len(items) == 2
    assert rec.rnn_model.h_t_1 is None

    # After feedback, hidden state should get initialized and updated
    for _ in range(5):
        chosen = items[0]
        reward = float(np.random.randn())
        rec.feedback(chosen, reward)
        items = rec.selection(n=2)

    assert rec.rnn_model.h_t_1 is not None

    # RNN parameters should have changed after some training
    rec.rnn_model.save_model()
    assert os.path.exists(storage)


def test_recommender_linucb_plus_rnn_updates_parameters(tmp_path):
    """
    Test that LinUCB+ feedback actually updates RNN parameters
    (through residual reward training).
    """
    storage = tmp_path / "recommender_plus2.npz"
    d = 4
    playlist = [MusicItem(id=i, feature_dim=d) for i in range(1, 5)]

    rec = Recommender(
        storage=str(storage),
        playlist=playlist,
        policy="LinUCB+",
        initialization=True,
        hidden_size=2 * d,
    )

    before = torch.cat([p.detach().view(-1) for p in rec.rnn_model.parameters()])

    for _ in range(10):
        items = rec.selection(n=1)
        item = items[0]
        reward = float(np.random.randn())
        rec.feedback(item, reward)

    after = torch.cat([p.detach().view(-1) for p in rec.rnn_model.parameters()])

    assert not torch.allclose(before, after), "RNN parameters did not change under LinUCB+ feedback."


if __name__ == "__main__":
    pytest.main([__file__])
