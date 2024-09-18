/**
 * Copyright (c) 2024 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package com.greenart7c3.citrine.utils

import android.util.Log
import com.greenart7c3.citrine.BuildConfig
import com.greenart7c3.citrine.Citrine
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Duration
import kotlin.properties.Delegates
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

object HttpClientManager {
    val DEFAULT_TIMEOUT_ON_WIFI: Duration = Duration.ofSeconds(10L)
    val DEFAULT_TIMEOUT_ON_MOBILE: Duration = Duration.ofSeconds(30L)

    var proxyChangeListeners = ArrayList<() -> Unit>()
    private var defaultTimeout = DEFAULT_TIMEOUT_ON_WIFI
    private var defaultHttpClient: OkHttpClient? = null

    // fires off every time value of the property changes
    private var internalProxy: Proxy? by
        Delegates.observable(null) { _, oldValue, newValue ->
            if (oldValue != newValue) {
                proxyChangeListeners.forEach { it() }
            }
        }

    fun setDefaultProxy(proxy: Proxy?) {
        if (internalProxy != proxy) {
            Log.d(Citrine.TAG, "Changing proxy to: ${proxy != null}")
            internalProxy = proxy

            // recreates singleton
            defaultHttpClient = buildHttpClient(internalProxy, defaultTimeout)
        }
    }

    fun setDefaultTimeout(timeout: Duration) {
        Log.d(Citrine.TAG, "Changing timeout to: $timeout")
        if (defaultTimeout.seconds != timeout.seconds) {
            defaultTimeout = timeout

            // recreates singleton
            defaultHttpClient = buildHttpClient(internalProxy, defaultTimeout)
        }
    }

    private fun buildHttpClient(
        proxy: Proxy?,
        timeout: Duration,
    ): OkHttpClient {
        val seconds = if (proxy != null) timeout.seconds * 2 else timeout.seconds
        val duration = Duration.ofSeconds(seconds)
        return OkHttpClient.Builder()
            .proxy(proxy)
            .readTimeout(duration)
            .connectTimeout(duration)
            .writeTimeout(duration)
            .addInterceptor(DefaultContentTypeInterceptor())
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    class DefaultContentTypeInterceptor : Interceptor {
        @Throws(IOException::class)
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest: Request = chain.request()
            val requestWithUserAgent: Request =
                originalRequest
                    .newBuilder()
                    .header("User-Agent", "Citrine/${BuildConfig.VERSION_NAME}")
                    .build()
            return chain.proceed(requestWithUserAgent)
        }
    }

    fun getHttpClient(): OkHttpClient {
        if (defaultHttpClient == null) {
            defaultHttpClient = buildHttpClient(internalProxy, defaultTimeout)
        }
        return defaultHttpClient!!
    }

    fun initProxy(
        useProxy: Boolean,
        hostname: String,
        port: Int,
    ): Proxy? {
        return if (useProxy) Proxy(Proxy.Type.SOCKS, InetSocketAddress(hostname, port)) else null
    }
}
