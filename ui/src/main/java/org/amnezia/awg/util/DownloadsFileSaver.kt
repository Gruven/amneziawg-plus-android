/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package org.amnezia.awg.util

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.amnezia.awg.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

class DownloadsFileSaver(private val context: ComponentActivity) {
    private lateinit var activityResult: ActivityResultLauncher<String>
    private lateinit var futureGrant: CompletableDeferred<Boolean>

    init {
        futureGrant = CompletableDeferred()
        activityResult = context.registerForActivityResult(ActivityResultContracts.RequestPermission()) { ret -> futureGrant.complete(ret) }
    }

    suspend fun save(name: String, @Suppress("UNUSED_PARAMETER") mimeType: String?, @Suppress("UNUSED_PARAMETER") overwriteExisting: Boolean) = run {
        withContext(Dispatchers.Main.immediate) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                activityResult.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                val granted = futureGrant.await()
                if (!granted) {
                    futureGrant = CompletableDeferred()
                    return@withContext null
                }
            }
            @Suppress("DEPRECATION") val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            withContext(Dispatchers.IO) {
                val file = File(path, name)
                if (!path.isDirectory && !path.mkdirs())
                    throw IOException(context.getString(R.string.create_output_dir_error))
                DownloadsFile(context, FileOutputStream(file), file.absolutePath, null)
            }
        }
    }

    class DownloadsFile(@Suppress("UNUSED_PARAMETER") private val context: Context, val outputStream: OutputStream, val fileName: String, @Suppress("UNUSED_PARAMETER") private val uri: Uri?) {
        suspend fun delete() = withContext(Dispatchers.IO) {
            File(fileName).delete()
        }
    }
}
