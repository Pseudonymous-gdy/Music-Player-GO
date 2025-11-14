package com.iven.musicplayergo.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RecommendService {
    val api: RecommendApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://10.120.16.195:6100/") // 替换为你的服务器IP和端口
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RecommendApi::class.java)
    }
}

