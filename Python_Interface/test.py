# test_linucb_with_theta_csv.py
# -*- coding: utf-8 -*-

import io
import numpy as np
import pandas as pd
import pytest

from Recommender import Recommender, MusicItem

def test_music_item_load_x_csv():
    '''
    Test MusicItem.load_x() with CSV input.
    '''
    path = "Python_Interface/test.csv"
    music_items = [MusicItem(id=i, feature_dim=5) for i in range(1,11)]
    for item in music_items:
        item.load_x(storage=path, id_col="ID")
        assert item.features.shape == (5,)

def test_recommender_loading():
    '''
    Test Initialization: whether Recommender loads parameters.
    '''
    ads = Recommender(playlist=[MusicItem(id=i, feature_dim=5) for i in range(1,11)])
    for param in ['_A', '_b']:
        param_dict = getattr(ads, param)
        assert len(param_dict) == 10
        for i in range(1,11):
            assert param_dict[i].shape == (5,5) if param == '_A' else (5,)

def test_recommender_selection():
    '''
    Test Recommender Fundamental Selection & Update Logic.
    '''
    ads = Recommender(playlist=[MusicItem(id=i, feature_dim=5) for i in range(1,11)],initialization=True)
    assert ads is not None
    # state that reward calculation θ_a follows a simple gaussian distribution
    def get_real_theta():
        return np.random.normal(loc=0.3, scale=1.0, size=5)
    # simulate 5 rounds of selection and feedback
    for round in range(5):
        selected_items = ads.selection(n=3)
        assert len(selected_items) == 3
        for item in selected_items:
            real_theta = get_real_theta()
            reward = float(np.dot(real_theta, item.features) + np.random.normal(0, 0.1))
            ads.feedback(item, reward)
    flag = False
    for param in ['_A', '_b']:
        param_dict = getattr(ads, param)
        for i in range(1,11):
            if param == '_A' and not np.array_equal(param_dict[i], np.eye(5)):
                flag = True
            if param == '_b' and not np.array_equal(param_dict[i], np.zeros(5)):
                flag = True
    assert flag, "Parameters did not update after feedback."

if __name__ == "__main__":
    pytest.main([__file__])
