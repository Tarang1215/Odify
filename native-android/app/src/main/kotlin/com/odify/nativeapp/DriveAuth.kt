package com.odify.nativeapp

import android.app.Activity
import android.content.Context
import android.accounts.Account
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
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
    private fun request(context: Context, scope: String = SCOPE): AuthorizationRequest {
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(scope)))
            .setOptOutIncludingGrantedScopes(true)
        context.getSharedPreferences("odify_drive_consent_state", 0)
            .getString("accountName", null)?.let {
                builder.setAccount(Account(it, "com.google"))
            }
        return builder.build()
    }
    private fun rememberAccount(context: Context, result: AuthorizationResult) {
        @Suppress("DEPRECATION")
        val name = result.toGoogleSignInAccount()?.account?.name ?: return
        // Account name only; access tokens and signing secrets are never persisted.
        context.getSharedPreferences("odify_drive_consent_state", 0).edit()
            .putString("accountName", name).apply()
    }

    // Uploads use a separately requested limited scope only when the user
    // explicitly selects files to save to their own Drive.
    const val UPLOAD_SCOPE = "https://www.googleapis.com/auth/drive.file"
    @Volatile private var uploadAccessToken: String? = null
    @Volatile private var uploadObtainedAt: Long = 0L

    fun authorizeUpload(
        activity: Activity,
        onToken: () -> Unit,
        onResolution: (android.app.PendingIntent) -> Unit,
        onError: (String) -> Unit
    ) {
        Identity.getAuthorizationClient(activity).authorize(request(activity, UPLOAD_SCOPE))
            .addOnSuccessListener { result ->
                when {
                    result.hasResolution() && result.pendingIntent != null ->
                        onResolution(result.pendingIntent!!)
                    !result.accessToken.isNullOrBlank() -> {
                        setUploadToken(result.accessToken!!)
                        onToken()
                    }
                    else -> onError("Drive 파일 저장 권한을 확인할 수 없습니다.")
                }
            }.addOnFailureListener {
                onError(it.localizedMessage ?: "Google Drive 저장 권한 오류")
            }
    }

    fun resolveUpload(activity: Activity, data: android.content.Intent?): Boolean {
        if (data == null) return false
        val token = Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(data).accessToken
        if (token.isNullOrBlank()) return false
        setUploadToken(token)
        return true
    }
    private fun setUploadToken(token: String) {
        uploadAccessToken = token
        uploadObtainedAt = System.currentTimeMillis()
    }
    fun uploadTokenBlocking(context: Context): String {
        val current = uploadAccessToken
        if (!current.isNullOrBlank() &&
            System.currentTimeMillis() - uploadObtainedAt < TOKEN_REFRESH_AFTER_MS)
            return current
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request(context, UPLOAD_SCOPE)),
            20, TimeUnit.SECONDS
        )
        if (result.hasResolution())
            throw java.io.IOException("Drive 업로드 권한이 만료되었습니다. 저장 버튼을 다시 누르세요.")
        return result.accessToken?.also(::setUploadToken)
            ?: throw java.io.IOException("Drive 업로드 권한이 없습니다.")
    }

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenObtainedAt: Long = 0L
    private const val TOKEN_REFRESH_AFTER_MS = 40L * 60L * 1000L
    @Volatile var connected: Boolean = false
        private set

    fun authorize(
        activity: Activity,
        onToken: (String) -> Unit,
        onResolution: (android.app.PendingIntent) -> Unit,
        onError: (String) -> Unit
    ) {
        Identity.getAuthorizationClient(activity).authorize(request(activity))
            .addOnSuccessListener { result ->
                when {
                    result.hasResolution() && result.pendingIntent != null ->
                        onResolution(result.pendingIntent!!)
                    !result.accessToken.isNullOrBlank() -> {
                        rememberAccount(activity, result)
                        setToken(result.accessToken!!)
                        onToken(result.accessToken!!)
                    }
                    else -> onError("Google Drive 권한을 확인할 수 없습니다.")
                }
            }.addOnFailureListener { onError(it.localizedMessage ?: "Google 인증 오류") }
    }

    fun resolve(activity: Activity, data: android.content.Intent?): String? {
        if (data == null) return null
        val result = Identity.getAuthorizationClient(activity)
            .getAuthorizationResultFromIntent(data)
        val token = result.accessToken
        if (!token.isNullOrBlank()) {
            rememberAccount(activity, result)
            setToken(token)
        }
        return token
    }

    fun setToken(token: String) {
        accessToken = token
        tokenObtainedAt = System.currentTimeMillis()
        connected = true
    }

    /**
     * Called exclusively from IO/Media3 loader threads. Google Play Services
     * quietly renews a previously granted token when possible. Consent is not
     * silently launched from background playback; missing consent is explicit.
     */
    fun tokenBlocking(context: Context): String {
        val cached = accessToken
        if (!cached.isNullOrBlank() &&
            System.currentTimeMillis() - tokenObtainedAt < TOKEN_REFRESH_AFTER_MS) {
            return cached
        }
        val result = Tasks.await(
            Identity.getAuthorizationClient(context).authorize(request(context)),
            20, TimeUnit.SECONDS
        )
        if (result.hasResolution()) {
            throw java.io.IOException("Google Drive 인증이 필요합니다. 앱에서 Drive 연결을 눌러주세요.")
        }
        rememberAccount(context, result)
        return result.accessToken?.also(::setToken)
            ?: throw java.io.IOException("Google Drive 액세스 토큰이 없습니다.")
    }

    fun tokenCached(): String? = accessToken

    fun invalidateBlocking(context: Context, rejected: String) {
        if (accessToken == rejected) { accessToken = null; tokenObtainedAt = 0L }
        Tasks.await(Identity.getAuthorizationClient(context).clearToken(
            ClearTokenRequest.builder().setToken(rejected).build()
        ), 20, TimeUnit.SECONDS)
    }

    fun clear() {
        accessToken = null
        tokenObtainedAt = 0L
        connected = false
    }
}
