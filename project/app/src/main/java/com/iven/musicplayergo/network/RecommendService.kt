package com.iven.musicplayergo.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RecommendService {
    private val client = OkHttpClient.Builder().build()

    val api: RecommendApi by lazy {
        Retrofit.Builder()
            .baseUrl(ArchiveService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RecommendApi::class.java)
    }
}
