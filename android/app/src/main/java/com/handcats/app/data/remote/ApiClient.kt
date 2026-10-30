package com.handcats.app.data.remote

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

object ApiClient {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    fun isLocalServer(baseUrl: String): Boolean {
        val host = normalize(baseUrl).toHttpUrlOrNull()!!.host
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        return host == "localhost" || host.endsWith(".local") || host == "::1" ||
            (host.contains(':') && (host.startsWith("fc") || host.startsWith("fd") ||
                host.startsWith("fe8") || host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb"))) ||
            (parts.size == 4 && (parts[0] == 10 || parts[0] == 127 ||
                (parts[0] == 192 && parts[1] == 168) ||
                (parts[0] == 172 && parts[1] in 16..31) ||
                (parts[0] == 169 && parts[1] == 254)))
    }
    fun normalize(baseUrl: String): String {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("请输入有效的 http:// 或 https:// 地址")
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null && url.encodedPath == "/") { "地址只需包含服务器和端口，例如 http://192.168.1.10:8000" }
        return url.toString()
    }
    fun client(token: () -> String?): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS).callTimeout(150, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false)
        .addInterceptor { chain ->
            val request = chain.request().newBuilder()
            token()?.let { request.header("Authorization", "Bearer $it") }
            chain.proceed(request.build())
        }.build()
    fun create(baseUrl: String, client: OkHttpClient): HandCatsApi = Retrofit.Builder()
        .baseUrl(normalize(baseUrl)).client(client)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build().create(HandCatsApi::class.java)
}
