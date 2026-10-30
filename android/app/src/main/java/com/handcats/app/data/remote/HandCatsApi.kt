package com.handcats.app.data.remote

import okhttp3.MultipartBody
import retrofit2.http.*

interface HandCatsApi {
    @GET("health/db") suspend fun health(): HealthResponse
    @POST("api/v1/auth/register") suspend fun register(@Body body: Registration): AuthResponse
    @POST("api/v1/auth/login") suspend fun login(@Body body: Credentials): AuthResponse
    @GET("api/v1/auth/me") suspend fun me(): User
    @PATCH("api/v1/auth/me") suspend fun nickname(@Body body: Nickname): User
    @POST("api/v1/auth/logout") suspend fun logout()
    @POST("api/v1/auth/delete-account") suspend fun deleteAccount(@Body body: AccountRemoval)
    @POST("api/v1/auth/password") suspend fun password(@Body body: PasswordChange)
    @GET("api/v1/catalog") suspend fun catalog(): List<CatType>
    @GET("api/v1/observations") suspend fun observations(@Query("offset") offset: Int = 0, @Query("limit") limit: Int = 30, @Query("cat_code") catCode: String? = null): ObservationPage
    @Multipart @POST("api/v1/observations") suspend fun upload(@Part file: MultipartBody.Part, @Header("X-Request-ID") requestId: String): Observation
    @PATCH("api/v1/observations/{id}") suspend fun edit(@Path("id") id: String, @Body body: ObservationEdit): Observation
    @POST("api/v1/observations/{id}/review") suspend fun review(@Path("id") id: String): ReviewResponse
    @POST("api/v1/observations/{id}/review/apply") suspend fun applyReview(@Path("id") id: String, @Body body: ReviewApply): Observation
    @DELETE("api/v1/observations/{id}") suspend fun delete(@Path("id") id: String)
}
