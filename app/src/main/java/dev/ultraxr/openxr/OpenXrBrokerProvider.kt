// Copyright 2026, Ultra XR contributors.
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.ultraxr.openxr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.io.File

/**
 * This app as the OpenXR "installable runtime broker", for phones without the Khronos OpenXR Runtime Broker app.
 *
 * The Khronos loader (OpenXR-SDK-Source, src/loader/android_utilities.cpp) asks
 *   content://org.khronos.openxr.runtime_broker/openxr/1/abi/arm64-v8a/runtimes/active
 * with the projection package_name, native_lib_dir, so_filename, has_functions, takes the first row whose
 * native_lib_dir + "/" + so_filename it can dlopen, and loads that library as the runtime. When has_functions is 1 it
 * also asks .../runtimes/<package>/functions for function_name, symbol_name rows; Monado exports the standard
 * xrNegotiateLoaderRuntimeInterface, so there are none.
 *
 * The loader checks nothing else: no signature, no permission (the Khronos broker's provider has no read
 * permission either). Do not declare the permission org.khronos.openxr.permission.OPENXR in this app.
 *
 * Android allows one installed package per provider authority: with this provider in the manifest, this app and the
 * Khronos broker app (package org.khronos.openxr.runtime_broker) cannot both be installed; whichever comes second
 * fails with INSTALL_FAILED_CONFLICTING_PROVIDER. Ship it in a build flavor, or leave it out and use the broker app.
 */
class OpenXrBrokerProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        // /openxr/<major>/abi/<abi>/runtimes/active, and the same with a row number as the broker app accepts.
        addURI(AUTHORITY, "openxr/#/abi/*/runtimes/active", ACTIVE)
        addURI(AUTHORITY, "openxr/#/abi/*/runtimes/active/#", ACTIVE_ROW)
        addURI(AUTHORITY, "openxr/#/abi/*/runtimes/*/functions", FUNCTIONS)
        addURI(AUTHORITY, "openxr/#/abi/*/runtimes/*/functions/#", FUNCTIONS)
    }

    override fun onCreate(): Boolean = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): Cursor? {
        val match = matcher.match(uri)
        if (match == UriMatcher.NO_MATCH) return null
        val segments = uri.pathSegments
        val major = segments[1].toIntOrNull() ?: return null
        val abi = segments[3]

        if (match == FUNCTIONS) {
            // No renamed functions. An empty table, for a caller that asks although has_functions is 0.
            return MatrixCursor(projection ?: arrayOf(FUNCTION_NAME, SYMBOL_NAME))
        }

        val columns = projection ?: arrayOf(ID, PACKAGE_NAME, NATIVE_LIB_DIR, SO_FILENAME, HAS_FUNCTIONS)
        val cursor = MatrixCursor(columns)
        // Only what was built: OpenXR 1.x, and the one ABI this install runs as (arm64-v8a on a phone; x86_64 in the
        // emulator test build). Anything else gets an empty table ("no active runtime").
        if (major != OPENXR_MAJOR || abi != android.os.Build.SUPPORTED_ABIS.firstOrNull { File(context!!.applicationInfo.nativeLibraryDir ?: "", SO_FILENAME_VALUE).exists() && context!!.applicationInfo.nativeLibraryDir.orEmpty().endsWith(abiDirName(it)) }) return cursor
        if (match == ACTIVE_ROW && segments.last() != "0") return cursor
        val libDir = nativeLibDir(abi) ?: return cursor

        val row = arrayOfNulls<Any>(columns.size)
        for ((i, column) in columns.withIndex()) {
            row[i] = when (column) {
                ID -> 0
                PACKAGE_NAME -> context!!.packageName
                NATIVE_LIB_DIR -> libDir
                SO_FILENAME -> SO_FILENAME_VALUE
                HAS_FUNCTIONS -> 0
                else -> throw IllegalArgumentException("unknown column $column")
            }
        }
        cursor.addRow(row)
        return cursor
    }

    /**
     * Where the application's process can dlopen the runtime from: the extracted library directory, or, when the APK
     * keeps its libraries inside (extractNativeLibs false), the path into the APK, as the Khronos broker gives it.
     * Monado finds this app's base.apk from that path (android_load_class.cpp, getRuntimeSourceDir), so the library
     * must be in the base APK, not in a split.
     */
    private fun nativeLibDir(abi: String): String? {
        val info = context?.applicationInfo ?: return null
        val extracted = info.nativeLibraryDir
        return if (extracted != null && File(extracted, SO_FILENAME_VALUE).exists()) extracted else info.sourceDir + "!/lib/" + abi
    }

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        ACTIVE -> "vnd.android.cursor.dir/vnd.$AUTHORITY.activeRuntime"
        ACTIVE_ROW -> "vnd.android.cursor.item/vnd.$AUTHORITY.activeRuntime"
        FUNCTIONS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.functions"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<String>?): Int = 0

    /** The name Android gives the library directory of an ABI (lib/arm64, lib/x86_64). */
    private fun abiDirName(abi: String) = when (abi) { "arm64-v8a" -> "arm64"; "armeabi-v7a" -> "arm"; else -> abi }

    private companion object {
        const val AUTHORITY = "org.khronos.openxr.runtime_broker"
        const val OPENXR_MAJOR = 1
        const val SO_FILENAME_VALUE = "libopenxr_monado.so"

        const val ID = "_id"
        const val PACKAGE_NAME = "package_name"
        const val NATIVE_LIB_DIR = "native_lib_dir"
        const val SO_FILENAME = "so_filename"
        const val HAS_FUNCTIONS = "has_functions"
        const val FUNCTION_NAME = "function_name"
        const val SYMBOL_NAME = "symbol_name"

        const val ACTIVE = 1
        const val ACTIVE_ROW = 2
        const val FUNCTIONS = 3
    }
}
