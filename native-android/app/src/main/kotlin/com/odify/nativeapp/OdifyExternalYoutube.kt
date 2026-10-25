package com.odify.nativeapp

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Public Android app links only: no other app's tokens, cache or audio URLs. */
internal object OdifyExternalYoutube {
    private val packages = listOf("app.morphe.android.youtube", "app.revanced.android.youtube",
        "app.revanced.android.apps.youtube.music", "com.google.android.youtube", "com.google.android.apps.youtube.music")
    fun openVideo(context: Context, id: String): Boolean {
        require(Regex("[A-Za-z0-9_-]{11}").matches(id))
        return open(context, Uri.parse("https://www.youtube.com/watch?v=$id"))
    }
    fun openSearch(context: Context, query: String): Boolean {
        require(query.trim().length in 1..160)
        return open(context, Uri.parse("https://www.youtube.com/results").buildUpon()
            .appendQueryParameter("search_query", query.trim()).build())
    }
    private fun open(context: Context, uri: Uri): Boolean {
        for (name in packages) {
            val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(intent) }.isSuccess) return true
        }
        // Let Android choose another installed handler/browser if no known app.
        return runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
