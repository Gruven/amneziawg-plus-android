/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package org.amnezia.awg.util;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.amnezia.awg.util.NonNullForAll;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;

@NonNullForAll
@RestrictTo(Scope.LIBRARY_GROUP)
public final class SharedLibraryLoader {
    private static final String TAG = "AmneziaWG/SharedLibraryLoader";

    private SharedLibraryLoader() {
    }

    public static boolean extractLibrary(final Context context, final String libName, final File destination) throws IOException {
        final Collection<String> apks = new HashSet<>();
        if (context.getApplicationInfo().sourceDir != null)
            apks.add(context.getApplicationInfo().sourceDir);
        @SuppressWarnings("deprecation")
        final String abi1 = Build.CPU_ABI;
        @SuppressWarnings("deprecation")
        final String abi2 = Build.CPU_ABI2;
        final String[] abis;
        if (abi2 != null && !abi2.isEmpty()) {
            abis = new String[]{abi1, abi2};
        } else {
            abis = new String[]{abi1};
        }

        for (final String abi : abis) {
            for (final String apk : apks) {
                try (final ZipFile zipFile = new ZipFile(new File(apk), ZipFile.OPEN_READ)) {
                    final String mappedLibName = System.mapLibraryName(libName);
                    final String libZipPath = "lib" + File.separatorChar + abi + File.separatorChar + mappedLibName;
                    final ZipEntry zipEntry = zipFile.getEntry(libZipPath);
                    if (zipEntry == null)
                        continue;
                    Log.d(TAG, "Extracting apk:/" + libZipPath + " to " + destination.getAbsolutePath());
                    try (final FileOutputStream out = new FileOutputStream(destination);
                         final InputStream in = zipFile.getInputStream(zipEntry)) {
                        int len;
                        final byte[] buffer = new byte[1024 * 32];
                        while ((len = in.read(buffer)) != -1) {
                            out.write(buffer, 0, len);
                        }
                        out.getFD().sync();
                    }
                }
                return true;
            }
        }
        return false;
    }

    public static void loadSharedLibrary(final Context context, final String libName) {
        Throwable noAbiException;
        try {
            System.loadLibrary(libName);
            return;
        } catch (final UnsatisfiedLinkError e) {
            Log.d(TAG, "Failed to load library normally, so attempting to extract from apk", e);
            noAbiException = e;
        }
        File f = null;
        try {
            final File codeCacheDir = new File(context.getCacheDir(), "code_cache");
            codeCacheDir.mkdirs();
            f = File.createTempFile("lib", ".so", codeCacheDir);
            if (extractLibrary(context, libName, f)) {
                System.load(f.getAbsolutePath());
                return;
            }
        } catch (final Exception e) {
            Log.d(TAG, "Failed to load library apk:/" + libName, e);
            noAbiException = e;
        } finally {
            if (f != null)
                // noinspection ResultOfMethodCallIgnored
                f.delete();
        }
        if (noAbiException instanceof RuntimeException)
            throw (RuntimeException) noAbiException;
        throw new RuntimeException(noAbiException);
    }
}
