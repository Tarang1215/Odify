package com.odify.nativeapp

import android.app.Activity
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * OAuth consent is performed on the phone, never by ChatGPT or the linked Drive
 * connector. Register the exact package + signing SHA-1 as an Android OAuth
 * client in Google Cloud and enable Google Drive API before using this.
 */
object DriveAuth {
    const val SCOPE = "https://www.googleapis.com/auth/drive.readonly"
    private val request: AuthorizationRequest by lazy {
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(SCOPE)))
            .build()
    }

    @Volatile private var accessToken: String? = null
    @Volatile var connected: Boolean = false
        private set

    fun authorize(
        activity: Activity,
        onToken: (String) -> Unit,
        onResolution: (android.app.PendingIntent) -> Unit,
        onError: (String) -> Unit
    ) {
        Identity.getAuthorizationClient(activity).authorize(request)
            .addOnSuccessListener { result ->
                when {
                    result.hasResolution() && result.pendingIntent != null ->
                        onResolution(result.pendingIntent!!)
                    !result.accessToken.isNullOrBlank() -> {
                        setToken(result.accessToken!!)
                        onToken(result.accessToken!!)
                    }
                    else -> onError("Google Drive 권한을 확인할 수 없습니다.")
                }
            }.addOnFailureListener { onError(it.localizedMessage ?: "Google 인증 오류") }
    }

    fun resolve(activity: Activity, data: android.content.Intent?): String? {
        if (data == null) return null
        val token = Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(data).accessToken
        if (!token.isNullOrBlank()) setToken(token)
        return token
    }

    fun setToken(token: String) {
        accessToken = token
        connected = true
    }

    /**
     * Called exclusively from IO/Media3 loader threads. Google Play Services
     * quietly renews a previously granted token when possible. Consent is not
     * silently launched from background playback; missing consent is explicit.
     */
    fun tokenBlocking(context: Context): String {
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request),
            20, TimeUnit.SECONDS
        )
        if (result.hasResolution()) {
            throw java.io.IOException("Google Drive 인증이 필요합니다. 앱에서 Drive 연결을 눌러주세요.")
        }
        return result.accessToken?.also(::setToken)
            ?: throw java.io.IOException("Google Drive 액세스 토큰이 없습니다.")
    }

    fun tokenCached(): String? = accessToken

    fun clear() {
        accessToken = null
        connected = false
    }
}
