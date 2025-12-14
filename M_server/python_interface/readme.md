Python Interface (models, algorithms in local recommendation)
Key Interface
class Recommender(storage, playlist): class for local recommendation

storage: database/file to access for feature learning and update.
playlist: actual music in the playlist
Attributes

selection(self, policy: str='random', n: int=1): function for selecting further n music

policy: string, ranging from {'random', 'LinUCB', 'LinUCB+'}. It's the policy to be implemented.
n: number of music to recommend.
Pretraining the RNN user model
The synthetic simulators in Python_Interface/Train can bootstrap the RNN preference model before deploying on-device:

cd /workspaces/Music-Player-GO
python Python_Interface/Train/training.py --episodes 20 --steps-per-episode 200 \
	--dim 32 --hidden-size 64 --storage Python_Interface/recommender_params.npz
The script generates a fake catalog, simulates user feedback, trains Recommender.RNN, and stores the weights in the shared NPZ file. Adjust --use-popularity to bias sampling, and point --storage to the same location used by the mobile recommender so it can reuse the pretrained parameters.