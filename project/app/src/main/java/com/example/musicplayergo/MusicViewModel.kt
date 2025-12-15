package com.example.musicplayergo

import android.app.Application
import android.content.res.Resources
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.example.musicplayergo.models.Album
import com.example.musicplayergo.models.Music
import com.example.musicplayergo.repository.MusicRepository
import com.example.musicplayergo.utils.MusicUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import javax.inject.Inject
import kotlin.random.Random


@HiltViewModel
class MusicViewModel @Inject constructor(
    application: Application,
    private val musicRepository: MusicRepository
) : AndroidViewModel(application) {

    /**
     * This is the job for all coroutines started by this ViewModel.
     * Cancelling this job will cancel all coroutines started by this ViewModel.
     */
    private val mViewModelJob = SupervisorJob()

    private val mHandler = CoroutineExceptionHandler { _, exception ->
        exception.printStackTrace()
        deviceMusic.value = null
    }

    private val mUiDispatcher = Dispatchers.Main
    private val mIoDispatcher = Dispatchers.IO + mViewModelJob + mHandler
    private val mUiScope = CoroutineScope(mUiDispatcher)

    val deviceMusic = MutableLiveData<MutableList<Music>?>()

    private var mDeviceMusicList = mutableListOf<Music>()

    fun getSongFromIntent(queriedDisplayName: String) =
        mDeviceMusicList.firstOrNull { s -> s.displayName == queriedDisplayName }

    var deviceMusicFiltered: MutableList<Music>? = null

    //keys: artist || value: its songs
    var deviceSongsByArtist: Map<String?, List<Music>>? = null

    //keys: album || value: its songs
    var deviceMusicByAlbum: Map<String?, List<Music>>? = null

    //keys: artist || value: albums
    var deviceAlbumsByArtist: MutableMap<String, List<Album>>? = mutableMapOf()

    //keys: artist || value: songs contained in the folder
    var deviceMusicByFolder: Map<String, List<Music>>? = null

    fun getRandomMusic(): Music? {
        deviceMusicFiltered?.shuffled()?.run {
           return get(Random.nextInt(size))
        }
        return deviceMusicFiltered?.random()
    }

    /**
     * Cancel all coroutines when the ViewModel is cleared
     */
    override fun onCleared() {
        super.onCleared()
        mViewModelJob.cancel()
    }

    fun cancel() {
        onCleared()
    }

    fun getDeviceMusic() {
        viewModelScope.launch {
            val music = withContext(Dispatchers.IO) {
                musicRepository.loadDeviceMusic()
            }
            if (music != null) {
                mDeviceMusicList = music.toMutableList()
                if (mDeviceMusicList.isNotEmpty()) {
                    buildLibrary(getApplication<Application>().resources)
                }
                deviceMusic.value = mDeviceMusicList
            } else {
                deviceMusic.value = null
            }
        }
    }

    private fun buildLibrary(resources: Resources) {
        // Removing duplicates by comparing everything except path which is different
        // if the same song is hold in different paths
        deviceMusicFiltered =
            mDeviceMusicList.distinctBy { it.artist to it.year to it.track to it.title to it.duration to it.album }
                .toMutableList()

        GoPreferences.getPrefsInstance().filters?.let { filter ->
            deviceMusicFiltered =  deviceMusicFiltered?.filter { music ->
               !filter.contains(music.artist) and !filter.contains(music.album) and !filter.contains(music.relativePath)
           }?.toMutableList()
        }

        deviceMusicFiltered?.let { dsf ->
            dsf.filterNot { GoPreferences.getPrefsInstance().filters?.contains(it.artist)!!}
            // group music by artist
            deviceSongsByArtist = dsf.groupBy { it.artist }
            deviceMusicByAlbum = dsf.groupBy { it.album }
            deviceMusicByFolder = dsf.groupBy { it.relativePath!! }
        }

        // group artists songs by albums
        deviceSongsByArtist?.keys?.iterator()?.let { iterate ->
            while (iterate.hasNext()) {
                iterate.next()?.let { artistKey ->
                    val album = deviceSongsByArtist?.getValue(artistKey)
                    deviceAlbumsByArtist?.set(
                        artistKey, MusicUtils.buildSortedArtistAlbums(resources, album)
                    )
                }
            }
        }
        updatePreferences()
    }

    private fun updatePreferences() {
        // update queue/favorites by updating moved songs id, albumId
        // and filtering out deleted songs

        val prefs = GoPreferences.getPrefsInstance()

        deviceMusicFiltered?.let { deviceMusic ->

            // update queue songs id
            prefs.queue?.run {
                val updatedQueue = map {
                    val music = findMusic(it)
                    it.copy(albumId = music?.albumId, id= music?.id)
                }
                // filter queue to remove songs not available on the device
                prefs.queue = updatedQueue.filter { deviceMusic.contains(it.copy(startFrom = 0))}
            }

            // update favorite songs id
            prefs.favorites?.run {
                val updatedFavorites = map {
                    val music = findMusic(it)
                    it.copy(albumId = music?.albumId, id= music?.id)
                }
                prefs.favorites = updatedFavorites.filter { deviceMusic.contains(it.copy(startFrom = 0))}
            }

            // check if pre queue song exists and update id
            if (prefs.isQueue != null) {
                prefs.isQueue?.let { preQueueSong ->
                    val found = findMusic(preQueueSong)
                    prefs.isQueue = if (found != null) {
                        preQueueSong.copy(albumId = found.albumId, id = found.id)
                    } else {
                        getRandomMusic()
                    }
                }
            }

            // check if latestPlayedSong exists and update id
            if (prefs.latestPlayedSong == null) {
                prefs.latestPlayedSong = getRandomMusic()
            } else {
                prefs.latestPlayedSong?.let { lps ->
                    val found = findMusic(lps)
                    prefs.latestPlayedSong = if (found != null) {
                        lps.copy(albumId = found.albumId, id = found.id)
                    } else {
                        // if queue had started, update latestPlayedSong
                        // picking the first queued song
                        prefs.queue?.let { queue ->
                            if (prefs.isQueue != null && queue.isNotEmpty()) {
                                prefs.latestPlayedSong = queue[0]
                            }
                            return
                        }
                        getRandomMusic()
                    }
                }
            }
        }
    }

    private fun findMusic(song: Music?): Music? {
        val songToFind = song?.copy(startFrom = 0)
        return deviceMusicFiltered?.find { newMusic ->
            songToFind?.title == newMusic.title && songToFind?.displayName == newMusic.displayName
                    && songToFind?.track == newMusic.track && songToFind.album == newMusic.album
                    && songToFind.year == newMusic.year && songToFind.duration == newMusic.duration
        }
    }
}
