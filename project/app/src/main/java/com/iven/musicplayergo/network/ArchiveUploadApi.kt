package com.iven.musicplayergo.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface ArchiveUploadApi {
    @Multipart
    @POST("upload_archive")
    suspend fun uploadArchive(
        @Part file: MultipartBody.Part,
        @Part("user_id") userId: RequestBody
    ): ArchiveUploadResponse
}

data class ArchiveUploadResponse(
    val ok: Boolean,
    val message: String?,
    val npz: String? = null
)