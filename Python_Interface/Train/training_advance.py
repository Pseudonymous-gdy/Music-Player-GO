"""Advanced pretraining for LinUCB+RNN recommender with dual losses.

This script wires together:
  - SongSimulator: per-user song catalog with latent genre structure.
  - UserSimulator: single-user preference dynamics (global + temporary).
  - Recommender:  LinUCB+RNN bandit algorithm (selection + feedback).

Goal:
  Train the RECOMMENDER ITSELF (not just the bare RNN) in a synthetic
  environment with many virtual users so that both the linear LinUCB
  component (A, b) and the RNN residual preference model are initialized
  before deployment.

Dual-loss design for the RNN:
  1) Reward loss (fit overall reward):
       L_reward = (theta_a^T x_t + beta_hat_t^T x_t - reward_t)^2

  2) Preference loss (teacher-forced from the simulator):
       L_pref   = ||beta_hat_t - beta_true_t||_2^2 / dim

  Combined:
       L = lambda_reward * L_reward + lambda_pref * L_pref

LinUCB parameters (A_a, b_a) are still updated in a closed-form
incremental way (no gradients), while the RNN is updated via its
internal Adam optimizer.

Usage (from repo root), for example:
    python Python_Interface/Train/training_advance.py --episodes 50 \\
        --lambda-reward 1.0 --lambda-pref 0.5
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
from Recommender import Recommender, RNN


# ----------------------------------------------------------------------
# Simple container for logging per-episode statistics
# ----------------------------------------------------------------------
@dataclass
class EpisodeStats:
    loss_combined: float
    loss_reward: float
    loss_pref: float
    reward: float
    steps: int

    def as_tuple(self) -> Tuple[float, float, float, float, int]:
        return (
            self.loss_combined,
            self.loss_reward,
            self.loss_pref,
            self.reward,
            self.steps,
        )


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
# RNN update with dual losses (reward + preference)
# ----------------------------------------------------------------------
def rnn_dual_loss_update(
    rnn: RNN,
    features: np.ndarray,
    reward: float,
    base_reward: float,
    beta_true: np.ndarray,
    lambda_reward: float,
    lambda_pref: float,
) -> Tuple[float, float, float, float]:
    """
    Perform one RNN update step with a combination of:
      - reward loss:
          L_reward = (theta^T x + beta_hat^T x - reward)^2
      - preference loss:
          L_pref   = ||beta_hat - beta_true||_2^2 / dim

    We treat the RNN as a standard online RNN:
      - Input:  x_t and previous hidden state h_{t-1} (stored in rnn.h_t_1).
      - Output: h_t, beta_hat_t.
      - Then we update rnn.h_t_1 = h_t for the next step.
    """
    # Ensure 1D numpy with correct dtype
    x_np = np.asarray(features, dtype=np.float32).reshape(-1)
    beta_true_np = np.asarray(beta_true, dtype=np.float32).reshape(-1)

    # Prepare tensors
    x_t = torch.from_numpy(x_np).float()
    beta_true_t = torch.from_numpy(beta_true_np).float()

    # Prepare previous hidden state
    if rnn.h_t_1 is None:
        h_prev = torch.zeros(rnn.hidden_size, dtype=torch.float32)
    else:
        h_prev = rnn.h_t_1.detach().float()

    # Forward pass: use current x_t and previous hidden state
    h_t, beta_hat = rnn.forward(x_t, h_prev)

    # Reward loss (full prediction)
    base_reward_t = torch.tensor(base_reward, dtype=torch.float32)
    target_reward_t = torch.tensor(reward, dtype=torch.float32)
    pred_total = base_reward_t + torch.dot(beta_hat, x_t)
    loss_reward = (pred_total - target_reward_t) ** 2

    # Preference loss (match simulator's true beta_t)
    # Use mean squared error over dimensions
    loss_pref = torch.mean((beta_hat - beta_true_t) ** 2)

    # Combined loss
    loss_combined = lambda_reward * loss_reward + lambda_pref * loss_pref

    # Backprop into RNN
    rnn.optimizer.zero_grad()
    loss_combined.backward()
    rnn.optimizer.step()

    # Update internal state for next step
    rnn.h_t_1 = h_t.detach()
    rnn.beta_t = beta_hat.detach()

    return (
        float(loss_combined.item()),
        float(loss_reward.item()),
        float(loss_pref.item()),
        float(pred_total.item()),
    )


# ----------------------------------------------------------------------
# Core: one episode where the Recommender chooses items and we apply dual-loss RNN updates
# ----------------------------------------------------------------------
def run_episode_with_recommender_dual(
    recommender: Recommender,
    user_sim: UserSimulator,
    steps: int,
    lambda_reward: float,
    lambda_pref: float,
) -> EpisodeStats:
    """
    Simulate a single listening session and train the RNN with two losses:
      - reward loss (fit overall reward)
      - preference loss (fit true preference vector from simulator)
    """
    total_reward = 0.0
    total_loss_combined = 0.0
    total_loss_reward = 0.0
    total_loss_pref = 0.0
    total_steps = 0

    for _ in range(steps):
        # 1) Algorithm selects one item (top-1)
        item = recommender.selection(n=1)[0]

        # 2) Environment (user) responds with a reward and internal info
        reward_out = user_sim.step(item, return_info=True)
        # step(..., return_info=True) returns (reward, info)
        reward, info = reward_out
        reward = float(reward)
        beta_true = info["beta_t"]  # np.ndarray, shape: (dim,)

        # 3) Linear part: theta_a = A^{-1} b, base_reward = theta_a^T x
        x_a = item.features  # numpy array
        theta_a = np.linalg.solve(recommender._A[item.id], recommender._b[item.id])
        base_reward = float(np.dot(theta_a, x_a))

        # 4) Dual-loss RNN update
        if recommender.policy == "LinUCB+":
            loss_comb, loss_reward, loss_pref, _ = rnn_dual_loss_update(
                rnn=recommender.rnn_model,
                features=x_a,
                reward=reward,
                base_reward=base_reward,
                beta_true=beta_true,
                lambda_reward=lambda_reward,
                lambda_pref=lambda_pref,
            )
            total_loss_combined += loss_comb
            total_loss_reward += loss_reward
            total_loss_pref += loss_pref

        # 5) Incremental update for LinUCB: A_a and b_a
        recommender._A[item.id] += np.outer(x_a, x_a)
        recommender._b[item.id] += reward * x_a
        recommender.last_selected_id = item.id

        total_reward += reward
        total_steps += 1

    return EpisodeStats(
        loss_combined=total_loss_combined,
        loss_reward=total_loss_reward,
        loss_pref=total_loss_pref,
        reward=total_reward,
        steps=total_steps,
    )


# ----------------------------------------------------------------------
# Argument parsing
# ----------------------------------------------------------------------
def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Advanced pretraining of LinUCB+RNN with dual losses (reward + preference)"
    )
    parser.add_argument(
        "--dim",
        type=int,
        default=32,
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
        default=500,
        help="Number of simulated listening sessions (episodes)",
    )
    parser.add_argument(
        "--steps-per-episode",
        type=int,
        default=100,
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
        default="Python_Interface/Train/recommender_params_advance.npz",
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
        default=1,
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
    parser.add_argument(
        "--lambda-reward",
        type=float,
        default=1.0,
        help="Weight for reward loss term L_reward",
    )
    parser.add_argument(
        "--lambda-pref",
        type=float,
        default=1.0,
        help="Weight for preference loss term L_pref",
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

    # 2) Build virtual users (force reward_mode="dot" for bandit-style training)
    users: List[UserSimulator]
    if args.num_users <= 1:
        # Single-user setting; optionally resample his global preference over episodes
        users = [
            UserSimulator(
                dim=args.dim,
                seed=args.seed,
                reward_mode="dot",
            )
        ]
    else:
        # Multi-user pool; each user has its own long-term preference
        users = [
            UserSimulator(
                dim=args.dim,
                seed=args.seed + i,
                reward_mode="dot",
            )
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

        # Run one full episode of interaction with dual-loss RNN updates
        stats = run_episode_with_recommender_dual(
            recommender=recommender,
            user_sim=user,
            steps=args.steps_per_episode,
            lambda_reward=args.lambda_reward,
            lambda_pref=args.lambda_pref,
        )

        steps = max(stats.steps, 1)
        avg_combined = stats.loss_combined / steps if stats.loss_combined > 0 else 0.0
        avg_reward_loss = stats.loss_reward / steps if stats.loss_reward > 0 else 0.0
        avg_pref_loss = stats.loss_pref / steps if stats.loss_pref > 0 else 0.0
        avg_reward = stats.reward / steps
        elapsed = time.time() - start
        print(
            f"Episode {episode:03d}/{args.episodes} | "
            f"avg_combined={avg_combined:.4f} | "
            f"avg_reward_loss={avg_reward_loss:.4f} | "
            f"avg_pref_loss={avg_pref_loss:.4f} | "
            f"avg_reward={avg_reward:.4f} | "
            f"elapsed={elapsed:.1f}s"
        )

    # 5) Save LinUCB and RNN parameters into the shared NPZ file
    recommender.save_params()
    if hasattr(recommender, "rnn_model"):
        recommender.rnn_model.save_model()

    print(f"Saved advanced pretrained Recommender (LinUCB+RNN) parameters to {storage_path}")


if __name__ == "__main__":
    main()
