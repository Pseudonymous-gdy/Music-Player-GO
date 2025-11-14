package com.iven.musicplayergo.network

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
        @Part("artist") artist: RequestBody? = null
    ): AudioUploadResponse
}

// 对应 FastAPI 返回：
// {
//   "song_id": "...",
//   "name": "...",
//   "artist": "...",
//   "feature_path": "...",
//   "meta": { ... },
//   "original_filename": "..."
// }
data class AudioUploadResponse(
    val song_id: String,
    val name: String?,
    val artist: String?,
    val feature_path: String?,
    val meta: Map<String, Any>?,       // 会被 Gson 解析成 LinkedTreeMap
    val original_filename: String?
)
