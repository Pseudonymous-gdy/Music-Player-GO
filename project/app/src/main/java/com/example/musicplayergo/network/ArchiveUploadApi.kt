package com.example.musicplayergo.network

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ArchiveUploadApi {

    // 对应 FastAPI:
    // @app.post("/api/audio/upload")
    @Multipart
    @POST("api/audio/upload")
    suspend fun uploadAudio(
        @Part file: MultipartBody.Part,
        // 可选 artist 字段，对应后端 Form(None)
        @Part("artist") artist: RequestBody? = null,
        // 可选 user_id 字段，兼容新增参数
        @Part("user_id") userId: RequestBody? = null
    ): AudioUploadResponse
}

// 对应 FastAPI 返回：
// {
//   "song_name": "...",
//   "already_exists": false,
//   ...
// }
data class AudioUploadResponse(
    @SerializedName("song_id")
    val songId: String? = null,
    @SerializedName("song_name")
    val songName: String? = null,
    val name: String? = null,
    val artist: String? = null,
    @SerializedName("feature_path")
    val featurePath: String? = null,
    val feature: String? = null,
    val meta: Map<String, Any>? = null,
    @SerializedName("original_filename")
    val originalFilename: String? = null,
    @SerializedName("already_exists")
    val alreadyExists: Boolean? = null
) {
    fun getServerSongId(): String? = songId ?: songName
}
