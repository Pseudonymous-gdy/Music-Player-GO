package com.iven.musicplayergo.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object BehaviorService {

    private val client = OkHttpClient.Builder().build()

    val api: BehaviorApi by lazy {
        Retrofit.Builder()
            .baseUrl(ArchiveService.BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BehaviorApi::class.java)
    }
}
