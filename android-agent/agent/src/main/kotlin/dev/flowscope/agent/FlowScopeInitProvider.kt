package dev.flowscope.agent

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri

/**
 * Installs the agent before `Application.onCreate`, so client apps need no source changes.
 * Only present in builds that depend on `dev.flowscope:agent`.
 */
internal class FlowScopeInitProvider : ContentProvider() {
	override fun onCreate(): Boolean {
		(context?.applicationContext as? Application)?.let { FlowScopeAgent.install(it) }
		return true
	}

	override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0
}
