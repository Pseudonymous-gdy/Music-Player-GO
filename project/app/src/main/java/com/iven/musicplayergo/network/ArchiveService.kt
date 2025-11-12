package com.iven.musicplayergo.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ArchiveService {
    // TODO: 把下面替换为你服务器在局域网上的 IP 和端口，末尾必须有 '/'
    private const val BASE_URL = "http://10.120.16.195/6000/"

    private val client = OkHttpClient.Builder().build()

    val api: ArchiveUploadApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ArchiveUploadApi::class.java)
    }
}