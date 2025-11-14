package com.iven.musicplayergo.network

import retrofit2.http.GET
import retrofit2.http.Query

interface RecommendApi {
    @GET("recommend")
    suspend fun getRecommend(@Query("user_id") userId: String): RecommendResponse
}