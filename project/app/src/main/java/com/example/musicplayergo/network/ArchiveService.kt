package com.example.musicplayergo.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object ArchiveService {
    // 把 IP 换成你服务器的局域网 IP；端口用 FastAPI 的 PORT（默认为 6000）
    // 注意这里要写成 :6000，而不是 /6000
    const val BASE_URL = "http://10.120.16.195:6000/"

    private val client = OkHttpClient.Builder().build()

    val api: ArchiveUploadApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ArchiveUploadApi::class.java)
    }

    fun buildAbsoluteUrl(path: String?): String? {
        if (path.isNullOrBlank()) return null
        return when {
            path.startsWith("http", true) -> path
            path.startsWith("/") -> BASE_URL + path.substring(1)
            else -> BASE_URL + path
        }
    }
}
