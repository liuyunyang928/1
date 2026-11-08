package io.github.liuyunyang928.noshake.core

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * Read-only configuration provider.
 *
 * Hooked processes live in a different UID and cannot read this app's private
 * SharedPreferences. They fetch a one-row snapshot through a plain `query()` (available on every
 * supported Android version) and cache it. The provider exposes no sensitive data and rejects
 * every write operation.
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val ctx = context ?: throw IllegalStateException("ConfigProvider is not attached")
        val cursor = MatrixCursor(COLUMNS)
        // Only the exact configuration URI is served; other paths get an empty result.
        if (uri.path != Contract.CONFIG_PATH) {
            return cursor
        }

        val store = ConfigStore(ctx)
        cursor.addRow(
            arrayOf(
                if (store.masterEnabled) 1 else 0,
                if (store.strictMode) 1 else 0,
                store.blockedPackages.joinToString(BLOCKED_SEPARATOR),
            ),
        )
        return cursor
    }

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = null

    private companion object {
        val COLUMNS = arrayOf("master", "strict", "blocked")
        // Package names never contain a comma, it is a safe separator for the packed list.
        const val BLOCKED_SEPARATOR = ","
    }
}
