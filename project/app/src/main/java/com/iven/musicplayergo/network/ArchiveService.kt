package com.iven.musicplayergo.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ArchiveService {
    // 把 IP 换成你服务器的局域网 IP；端口用 FastAPI 的 PORT（默认为 6000）
    // 注意这里要写成 :6000，而不是 /6000
    private const val BASE_URL = "http://10.120.16.195:6000/"

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
