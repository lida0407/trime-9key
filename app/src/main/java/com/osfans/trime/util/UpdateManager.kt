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
        /**
         * Build time of the published APK, in epoch millis (see
         * [BuildConfig.BUILD_TIMESTAMP]). Commit hashes have no ordering, so
         * this is what tells "newer" from "older" -- without it the app
         * happily offers, and installs, a DOWNGRADE whenever the manifest
         * points at any build other than the installed one.
         */
        val buildTimestamp: Long = 0L,
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

    /**
     * True if [release] is strictly NEWER than the installed build.
     *
     * Deliberately not "the hashes differ": that offered a downgrade any time
     * the manifest lagged the installed build, and one tap on the dialog then
     * silently rolled the user back to an older APK.
     */
    fun isUpdateAvailable(release: Release): Boolean {
        if (release.commit.isBlank() || release.apkUrl.isBlank()) return false
        if (release.commit.equals(BuildConfig.BUILD_COMMIT_HASH, ignoreCase = true)) return false
        if (release.buildTimestamp <= 0L) {
            // A manifest without a build time can't be ordered against this
            // build; refusing is the safe answer (worst case: no update
            // prompt), offering it is how downgrades happened.
            Timber.w("Update manifest has no buildTimestamp; ignoring it")
            return false
        }
        return release.buildTimestamp > BuildConfig.BUILD_TIMESTAMP
    }

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

    private const val PREFS = "trime9key_update"
    private const val KEY_SKIPPED_COMMIT = "skipped_commit"
    private const val KEY_AUTO_CHECK = "auto_check"

    /** Whether the launch-time check may prompt at all. */
    fun isAutoCheckEnabled(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_CHECK, true)

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_CHECK, enabled)
            .apply()
    }

    /** Remember that the user doesn't want to be asked about [commit] again. */
    fun skipVersion(context: Context, commit: String) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SKIPPED_COMMIT, commit)
            .apply()
    }

    fun isSkipped(context: Context, commit: String): Boolean =
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SKIPPED_COMMIT, null)
            ?.equals(commit, ignoreCase = true) == true
}

/**
 * Check for an update and, if one is available, prompt the user.
 *
 * @param silent when true (e.g. the automatic check on launch), stay quiet
 *   unless there is actually a newer build; when false (manual "check for
 *   updates"), also report "up to date" / failure so the tap gives feedback.
 */
fun FragmentActivity.checkForUpdate(silent: Boolean = true) {
    if (silent && !UpdateManager.isAutoCheckEnabled(this)) return
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
        // The automatic check used to re-open this blocking dialog on EVERY
        // launch until the user updated. Honour "skip this version" for it;
        // an explicit "check for updates" tap still always answers.
        if (silent && UpdateManager.isSkipped(this@checkForUpdate, release.commit)) return@launch
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
            }.setNeutralButton(R.string.update__later, null)
            // "Skip" replaces the old browser link: downloadAndInstall already
            // falls back to the browser on failure, whereas there was no way
            // at all to stop the dialog coming back every launch.
            .setNegativeButton(R.string.update__skip_version) { _, _ ->
                UpdateManager.skipVersion(this@checkForUpdate, release.commit)
            }.show()
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
