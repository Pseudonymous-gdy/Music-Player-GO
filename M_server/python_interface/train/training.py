"""Pretrain the full LinUCB+RNN recommender with simulated users and songs.

This script wires together:
  - SongSimulator: per-user song catalog with latent genre structure.
  - UserSimulator: single-user preference dynamics (global + temporary).
  - Recommender:  LinUCB+RNN bandit algorithm (selection + feedback).

Goal:
  Train the RECOMMENDER ITSELF (not just the bare RNN) in a synthetic
  environment with many virtual users so that both the linear LinUCB
  component (A, b) and the RNN residual preference model are initialized
  before deployment.

Usage (from repo root), for example:
    python Python_Interface/Train/training.py --episodes 20

The script saves:
  - LinUCB parameters (A_i, b_i) via Recommender.save_params()
  - RNN parameters (rnn_*) via Recommender.rnn_model.save_model()

into the shared NPZ file at --storage. The on-device recommender can
then load these parameters with initialization=False.
"""

from __future__ import annotations

import argparse
import random
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Tuple, List

import numpy as np
import torch

# ----------------------------------------------------------------------
# Local imports (keep relative path stable regardless of invocation cwd)
# ----------------------------------------------------------------------
CURRENT_DIR = Path(__file__).resolve().parent
PARENT_DIR = CURRENT_DIR.parent
if str(PARENT_DIR) not in sys.path:
    sys.path.append(str(PARENT_DIR))

from SongSimulator import SongSimulator
from UserSimulator import UserSimulator
from Recommender import Recommender


# ----------------------------------------------------------------------
# Simple container for logging per-episode statistics
# ----------------------------------------------------------------------
@dataclass
class EpisodeStats:
    loss: float   # here we use a proxy MSE between predicted and true reward
    reward: float
    steps: int

    def as_tuple(self) -> Tuple[float, float, int]:
        return self.loss, self.reward, self.steps


# ----------------------------------------------------------------------
# Utility: seed everything for reproducibility
# ----------------------------------------------------------------------
def seed_everything(seed: Optional[int]) -> None:
    """Seed Python, NumPy, and torch for repeatable experiments."""
    if seed is None:
        return
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)


# ----------------------------------------------------------------------
# Core: one episode where the Recommender chooses items and learns
# ----------------------------------------------------------------------
def run_episode_with_recommender(
    recommender: Recommender,
    user_sim: UserSimulator,
    steps: int,
) -> EpisodeStats:
    """
    Simulate a single listening session.

    At each step:
      1. Recommender selects an item using LinUCB+RNN (selection()).
      2. UserSimulator generates a reward given that item.
      3. We compute the current predicted reward (linear + RNN) for logging.
      4. Recommender.feedback() updates both LinUCB (A, b) and the RNN
         residual model.

    This matches the actual online algorithm logic as closely as possible.
    """
    total_reward = 0.0
    total_sq_error = 0.0
    total_steps = 0

    for _ in range(steps):
        # 1) Algorithm selects one item (top-1)
        item = recommender.selection(n=1)[0]

        # 2) Environment (user) responds with a reward
        reward = float(user_sim.step(item))

        # 3) Compute current predicted reward BEFORE the update (for logging)
        x_a = item.features
        # Linear part: theta_a = A^{-1} b
        theta_a = np.linalg.solve(recommender._A[item.id], recommender._b[item.id])
        base_reward = float(np.dot(theta_a, x_a))

        pred_reward = base_reward
        if recommender.policy == "LinUCB+":
            # RNN contribution
            _, beta_t = recommender.rnn_model.forward(x_a, recommender.rnn_model.h_t_1)
            beta_t_np = beta_t.detach().cpu().numpy()
            pred_reward += float(np.dot(beta_t_np, x_a))

        # Squared error as a proxy for loss
        total_sq_error += (pred_reward - reward) ** 2

        # 4) Feed back the *true* reward; this updates A, b and the RNN residual
        recommender.feedback(item, reward)

        total_reward += reward
        total_steps += 1

    mse = total_sq_error / max(total_steps, 1)
    return EpisodeStats(loss=mse, reward=total_reward, steps=total_steps)


# ----------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Pretrain LinUCB+RNN Recommender with synthetic users and songs"
    )
    parser.add_argument(
        "--dim",
        type=int,
        default=128,
        help="Feature dimension for songs and RNN",
    )
    parser.add_argument(
        "--n-songs",
        type=int,
        default=500,
        help="Number of songs in simulated catalog",
    )
    parser.add_argument(
        "--n-genres",
        type=int,
        default=32,
        help="Latent genre clusters for feature generation",
    )
    parser.add_argument(
        "--episodes",
        type=int,
        default=100,
        help="Number of simulated listening sessions (episodes)",
    )
    parser.add_argument(
        "--steps-per-episode",
        type=int,
        default=50,
        help="Number of interaction steps per episode",
    )
    parser.add_argument(
        "--hidden-size",
        type=int,
        default=64,
        help="Hidden size of the RNN inside the Recommender",
    )
    parser.add_argument(
        "--alpha",
        type=float,
        default=1.0,
        help="Exploration parameter alpha for LinUCB",
    )
    parser.add_argument(
        "--l2",
        type=float,
        default=1.0,
        help="L2 regularization for LinUCB's A matrices (diagonal initialization)",
    )
    parser.add_argument(
        "--discount",
        type=float,
        default=0.7,
        help="Discount factor for re-recommending the same item in Recommender",
    )
    parser.add_argument(
        "--storage",
        type=str,
        default="Python_Interface/Train/recommender_params.npz",
        help="NPZ path used by Recommender to store A, b, and RNN weights",
    )
    parser.add_argument(
        "--seed",
        type=int,
        default=50,
        help="Random seed for reproducibility",
    )
    parser.add_argument(
        "--policy",
        type=str,
        default="LinUCB+",
        choices=["LinUCB+"],
        help="Recommender policy; currently we pretrain only LinUCB+ (with RNN)",
    )
    parser.add_argument(
        "--resample-global",
        type=int,
        default=100,
        help=(
            "If num_users == 1: resample long-term user preference every N episodes "
            "(0 disables; same user, slowly drifting). "
            "If num_users > 1: ignored (each user has its own fixed long-term pref)."
        ),
    )
    parser.add_argument(
        "--num-users",
        type=int,
        default=1000,
        help=(
            "Number of virtual users in the pool. "
            "If >1, each user is a separate UserSimulator with its own long-term preference; "
            "each episode randomly picks one user."
        ),
    )
    parser.add_argument(
        "--resume",
        action="store_true",
        help=(
            "If set, load existing A, b, and RNN parameters from --storage and continue "
            "training instead of reinitializing them."
        ),
    )
    return parser


# ----------------------------------------------------------------------
# Main entry point
# ----------------------------------------------------------------------
def main():
    args = build_parser().parse_args()
    seed_everything(args.seed)

    # 1) Build synthetic song catalog
    song_sim = SongSimulator(
        dim=args.dim,
        n_songs=args.n_songs,
        n_genres=args.n_genres,
        seed=args.seed,
    )
    playlist = song_sim.get_catalog()

    # 2) Build virtual users
    users: List[UserSimulator]
    if args.num_users <= 1:
        # Single-user setting; optionally resample his global preference over episodes
        users = [UserSimulator(dim=args.dim, seed=args.seed)]
    else:
        # Multi-user pool; each user has its own long-term preference
        users = [
            UserSimulator(dim=args.dim, seed=args.seed + i)
            for i in range(args.num_users)
        ]

    # 3) Prepare storage path
    storage_path = Path(args.storage)
    if not storage_path.parent.exists():
        storage_path.parent.mkdir(parents=True, exist_ok=True)

    # 4) Instantiate Recommender with LinUCB+ policy and internal RNN
    recommender = Recommender(
        storage=str(storage_path),
        playlist=playlist,
        alpha=args.alpha,
        l2=args.l2,
        initialization=not args.resume,  # if resume, try to load from storage
        policy=args.policy,
        discount=args.discount,
        hidden_size=args.hidden_size,    # forwarded via **kwargs to RNN
    )

    start = time.time()
    for episode in range(1, args.episodes + 1):
        # Pick which virtual user to use this episode
        if args.num_users <= 1:
            user = users[0]
            # Optional: resample this user's long-term preference to simulate a "new user"
            if args.resample_global and (episode % args.resample_global == 0):
                user.reset(resample_global=True)
            else:
                user.reset(resample_global=False)
        else:
            # Multi-user pool: choose one and reset only the short-term state
            user = random.choice(users)
            user.reset(resample_global=False)

        # Run one full episode of interaction
        stats = run_episode_with_recommender(
            recommender=recommender,
            user_sim=user,
            steps=args.steps_per_episode,
        )

        avg_loss = stats.loss  # already mean squared error over steps
        avg_reward = stats.reward / max(stats.steps, 1)
        elapsed = time.time() - start
        print(
            f"Episode {episode:03d}/{args.episodes} | "
            f"avg proxy MSE={avg_loss:.4f} | avg reward={avg_reward:.4f} | elapsed={elapsed:.1f}s"
        )

    # 5) Save LinUCB and RNN parameters into the shared NPZ file
    recommender.save_params()
    if hasattr(recommender, "rnn_model"):
        recommender.rnn_model.save_model()

    print(f"Saved pretrained Recommender (LinUCB+RNN) parameters to {storage_path}")


if __name__ == "__main__":
    main()