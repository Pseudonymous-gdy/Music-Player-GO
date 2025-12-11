package com.iven.musicplayergo.network

import retrofit2.http.Body
import retrofit2.http.POST

interface BehaviorApi {
    @POST("api/behavior/event")
    suspend fun sendEvent(@Body payload: BehaviorEventPayload)

    @POST("api/behavior/predict")
    suspend fun predict(@Body request: BehaviorPredictRequest): BehaviorPredictResponse
}
