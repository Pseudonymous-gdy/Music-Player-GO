import torch
from torch import nn
from torch import tensor
import numpy as np
import pandas as pd
import sqlite3
import random
from typing import Optional, Union

class Recommender():
    def __init__(
        self, 
        storage: Optional[str] = None, 
        playlist: Optional[Union[list, torch.Tensor, np.ndarray, pd.Series]] = None
    ):
        '''class for local recommendation.

        Require:
        - storage: database/file to access for feature learning and update.
        - playlist: actual music in the playlist.
        '''
        self.storage = storage
        self.playlist = playlist if playlist is not None else []
        # access storage based on playlist
        for music_item in self.playlist:
            pass

    def selection(self, policy: str = 'random', n: int = 1) -> list:
        '''
        function for selecting further n music.

        Require:
        - policy: string, ranging from {'random', 'LinUCB', 'LinUCB+'}. It's the policy to be implemented.
        - n: number of music to recommend.

        Return:
        - a list of n music items.
        '''
        assert policy in ['random', 'LinUCB', 'LinUCB+']
        if policy == 'random':
            output = []
            for i in range(n):
                index = random.randint(0, len(self.playlist) - 1)
                output.append(self.playlist[index])
            return output
        # Other policies to be implemented
        return []
