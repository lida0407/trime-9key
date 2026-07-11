/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.BuildConfig
import com.osfans.trime.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal self-update mechanism for the trime-9key fork.
 *
 * The published APK lives at `dist/` in the repository, alongside a
 * `dist/latest.json` manifest. The app fetches that manifest, compares the
 * `commit` it advertises against the commit this build was compiled from
 * ([BuildConfig.BUILD_COMMIT_HASH]) and, if they differ, offers to download
 * and install the new APK. No dedicated update server is required.
 */
object UpdateManager {
    // Raw manifest published in the repo. Kept on the same branch we build/push.
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/lida0407/trime-9key/develop/dist/latest.json"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Release(
        val versionName: String = "",
        val commit: String = "",
        val apkUrl: String = "",
        val notes: String = "",
    )

    /** Fetch the latest published release manifest, or null on any failure. */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            conn.inputStream.use { it.bufferedReader().readText() }
                .let { json.decodeFromString<Release>(it) }
        }.onFailure { Timber.w(it, "Failed to fetch update manifest") }.getOrNull()
    }

    /** True if [release] is a different build than the one currently installed. */
    fun isUpdateAvailable(release: Release): Boolean =
        release.commit.isNotBlank() &&
            release.apkUrl.isNotBlank() &&
            !release.commit.equals(BuildConfig.BUILD_COMMIT_HASH, ignoreCase = true)

    /** Download [url] into [dest]; returns true on success. */
    suspend fun download(url: String, dest: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
            true
        }.onFailure { Timber.w(it, "Failed to download apk") }.getOrDefault(false)
    }

    /** Launch the system package installer for [apk]. */
    fun installApk(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${BuildConfig.APPLICATION_ID}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openInBrowser(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/**
 * Check for an update and, if one is available, prompt the user.
 *
 * @param silent when true (e.g. the automatic check on launch), stay quiet
 *   unless there is actually a newer build; when false (manual "check for
 *   updates"), also report "up to date" / failure so the tap gives feedback.
 */
fun FragmentActivity.checkForUpdate(silent: Boolean = true) {
    lifecycleScope.launch {
        val release = UpdateManager.fetchLatest()
        if (release == null) {
            if (!silent) toast(R.string.update__check_failed)
            return@launch
        }
        if (!UpdateManager.isUpdateAvailable(release)) {
            if (!silent) toast(R.string.update__already_latest)
            return@launch
        }
        if (isFinishing || isDestroyed) return@launch
        AlertDialog
            .Builder(this@checkForUpdate)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setTitle(R.string.update__available_title)
            .setMessage(
                getString(
                    R.string.update__available_message,
                    release.versionName.ifBlank { "?" },
                    release.notes,
                ),
            ).setPositiveButton(R.string.update__download) { _, _ ->
                downloadAndInstall(release)
            }.setNeutralButton(R.string.update__open_browser) { _, _ ->
                UpdateManager.openInBrowser(this@checkForUpdate, release.apkUrl)
            }.setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private fun FragmentActivity.downloadAndInstall(release: UpdateManager.Release) {
    lifecycleScope.launch {
        toast(R.string.update__downloading)
        val dest = File(externalCacheDir ?: cacheDir, "trime-9key-update.apk")
        if (UpdateManager.download(release.apkUrl, dest)) {
            UpdateManager.installApk(this@downloadAndInstall, dest)
        } else {
            toast(R.string.update__download_failed)
            UpdateManager.openInBrowser(this@downloadAndInstall, release.apkUrl)
        }
    }
}
