package com.odify.nativeapp

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import java.io.IOException

/** Inject auth only for Drive audio, and retry a rejected token exactly once. */
internal class OdifyDriveDataSource(
    private val upstream: DataSource,
    private val token: () -> String,
    private val invalidate: (String) -> Unit
) : DataSource {
    override fun addTransferListener(listener: TransferListener) = upstream.addTransferListener(listener)
    override fun getUri(): Uri? = upstream.uri
    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)
    override fun close() = upstream.close()

    override fun open(spec: DataSpec): Long = try {
        openAuthenticated(spec)
    } catch (failure: IOException) {
        throw failure
    } catch (failure: Exception) {
        // Google Tasks can throw ExecutionException/TimeoutException. Media3
        // expects IO failures so consent/network errors remain recoverable.
        throw IOException("Drive 인증을 확인하세요. 앱에서 다시 연결할 수 있습니다.", failure)
    }

    private fun openAuthenticated(spec: DataSpec): Long {
        val uri = spec.uri
        if (uri.scheme != "https" || uri.host != "www.googleapis.com" ||
            uri.path?.startsWith("/drive/v3/files/") != true ||
            uri.getQueryParameter("alt") != "media") return upstream.open(spec)
        fun authenticated(value: String) = spec.buildUpon().setHttpRequestHeaders(
            spec.httpRequestHeaders + ("Authorization" to "Bearer $value")
        ).build()
        val first = token()
        return try {
            upstream.open(authenticated(first))
        } catch (failure: HttpDataSource.InvalidResponseCodeException) {
            if (failure.responseCode != 401) throw failure
            runCatching { upstream.close() }
            invalidate(first)
            upstream.open(authenticated(token()))
        }
    }
}
