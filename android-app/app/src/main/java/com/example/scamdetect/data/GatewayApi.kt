package com.example.scamdetect.data

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface GatewayApi {
    @POST("v1/utterance")
    suspend fun utterance(@Body body: UtteranceRequest): Response<UtteranceResponse>

    @GET("healthz")
    suspend fun health(): Response<Map<String, Any>>

    /** Workstream B: the call is over -> final verdict; the server clears the call's memory. */
    @POST("v1/call/end")
    suspend fun endCall(@Body body: EndCallRequest): Response<EndCallResponse>

    /** Workstream H.3: a consented report of a finished call. */
    @POST("v1/report")
    suspend fun report(@Body body: ReportRequest): Response<ReportResponse>

    /** Workstream G.3 (Hybrid): BERT's opinion on the caller's last 5 lines, masked on the phone. Stateless. */
    @POST("v1/verify")
    suspend fun verify(@Body body: VerifyRequest): Response<VerifyResponse>
}

/** Builds one Retrofit client per base URL, so changing the URL in Settings takes effect at once. */
object GatewayClient {
    private var cachedUrl: String? = null
    private var cachedApi: GatewayApi? = null

    /** "192.168.1.23:8080" -> "http://192.168.1.23:8080/" (Retrofit needs the scheme and the trailing /). */
    fun normalize(url: String): String {
        var u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        if (!u.endsWith("/")) u += "/"
        return u
    }

    /** Throws IllegalArgumentException if the URL is not valid. */
    @Synchronized
    fun get(baseUrl: String): GatewayApi {
        val url = normalize(baseUrl)
        cachedApi?.let { if (url == cachedUrl) return it }

        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val http = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .callTimeout(4, TimeUnit.SECONDS)   // gateway promises an answer within 2 s
            .addInterceptor(logging)            // logs method, URL, status only (no body)
            .build()

        val api = Retrofit.Builder()
            .baseUrl(url)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GatewayApi::class.java)

        cachedUrl = url
        cachedApi = api
        return api
    }
}
