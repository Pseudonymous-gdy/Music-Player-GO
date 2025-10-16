package com.iven.musicplayergo.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RecommendService {
    val api: RecommendApi by lazy {
        Retrofit.Builder()
            .baseUrl("http://10.120.16.195:6100") // 替换为你的服务器IP和端口
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(RecommendApi::class.java)
    }
}

// 简单调用示例（可放在 ViewModel 或 Activity 中）
import kotlinx.coroutines.*

fun testRecommendApi() {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val response = RecommendService.api.getRecommend("testuser")
            println("推荐歌曲: ${response.songs}")
        } catch (e: Exception) {
            println("请求失败: ${e.message}")
        }
    }
}