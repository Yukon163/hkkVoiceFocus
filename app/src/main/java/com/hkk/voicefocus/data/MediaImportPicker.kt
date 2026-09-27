package com.hkk.voicefocus.data

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Use MT's public picker contract, without depending on its obfuscated activity name. */
object MediaImportPicker {
    private const val MT_PACKAGE = "bin.mt.plus"
    private val mediaTypes = arrayOf("audio/*", "video/*")

    fun preferredIntent(context: Context): Intent {
        val mt = mediaIntent(Intent.ACTION_GET_CONTENT).setPackage(MT_PACKAGE)
        return if (mt.resolveActivity(context.packageManager) != null) mt else systemIntent()
    }

    fun systemIntent(): Intent = mediaIntent(Intent.ACTION_OPEN_DOCUMENT)

    fun selectedUri(result: Intent?): Uri? = result?.data
        ?: result?.clipData?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.uri

    private fun mediaIntent(action: String) = Intent(action).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        putExtra(Intent.EXTRA_MIME_TYPES, mediaTypes)
        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
