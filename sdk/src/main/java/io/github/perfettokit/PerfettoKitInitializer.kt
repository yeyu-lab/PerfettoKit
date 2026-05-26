package io.github.perfettokit

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * 自动初始化 — 通过 ContentProvider 在 App 启动时自动调用 PerfettoKit.init()。
 *
 * 原理:
 *   Android 系统在 Application.onCreate() 之前会执行所有 ContentProvider.onCreate()，
 *   SDK 的 Manifest 中声明此 Provider，Gradle 打包时自动合并，开发者无需手动初始化。
 *
 * 如果开发者想自定义配置:
 *   1. 在自己的 Application.onCreate() 中先调用 PerfettoKit.init(this, customConfig)
 *   2. 本 Provider 的 init 调用会被 `if (initialized) return` 跳过
 *
 * 如果想完全禁用自动初始化:
 *   在 App 的 AndroidManifest.xml 中:
 *   <provider
 *       android:name="io.github.perfettokit.PerfettoKitInitializer"
 *       android:authorities="${applicationId}.perfettokit-init"
 *       tools:node="remove" />
 */
class PerfettoKitInitializer : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application
        if (app != null) {
            PerfettoKit.autoInit(app)
        }
        return true
    }

    override fun query(
        uri: Uri, projection: Array<out String>?,
        selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
