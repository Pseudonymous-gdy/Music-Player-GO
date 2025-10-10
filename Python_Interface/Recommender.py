import torch
from torch import nn
from torch import tensor
import numpy as np
import pandas as pd
import sqlite3
import random

class Recommender():
  def __init__(self, storage: str|None = None, playlist: Optional[list, tensor, np.array, pd.Series, None]):
    '''class for local recommendation.

    Require:
    - storage: database/file to access for feature learning and update.
    - playlist: actual music in the playlist.
    
    '''
    self.storage = storage
    self.playlist = playlist
    # access storage based on playlist
    for music_item in playlist:
      pass

  def selection(self, policy: str='random', n: int=1) -> list:
    '''
    function for selecting further n music.

    Require:
    - policy: string, ranging from {'random', 'LinUCB', 'LinUCB+'}. It's the policy to be implemented.
    - n: number of music to recommend.

    Return:
    - a list of n music items.
    '''
    assert policy in ['random','LinUCB','LinUCB+']
    if policy == 'random':
      output = []
      for i in len(n):
        index = random.randint(0, len(playlist))
        output.append(playlist[index])
      return output

