package com.iven.musicplayergo.network

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface RecommendApi {
    @POST("api/recommend/query")
    suspend fun queryRecommendation(@Body request: RecommendQueryRequest): RecommendResponse

    @GET("api/songs")
    suspend fun listSongs(): SongsResponse
}
