package com.fnyoat.donothingvpn

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipInputStream

object GitHubUpdater {
    private const val OWNER = "fnyoat"
    private const val REPO = "DoNothingVPN"
    private const val WORKFLOW = "rename-build.yml"
    private const val WORKFLOW_NAME = "Build Renamed APK"
    private const val BRANCH = "main"
    private const val API = "https://api.github.com"
    private const val TAG = "GitHubUpdater"

    fun dispatchRename(token: String, name: String): Boolean {
        val body = JSONObject()
            .put("ref", BRANCH)
            .put("inputs", JSONObject().put("session_name", name))
            .toString()
        return request(token, "$API/repos/$OWNER/$REPO/actions/workflows/$WORKFLOW/dispatches", "POST", body) != null
    }

    fun findLatestCompletedRun(token: String, afterMs: Long): JSONObject? {
        val resp = request(token, "$API/repos/$OWNER/$REPO/actions/runs?event=workflow_dispatch&branch=$BRANCH&per_page=20")
            ?: return null
        val runs = JSONObject(resp).optJSONArray("runs") ?: return null
        val afterIso = toIsoUtc(afterMs)
        for (i in 0 until runs.length()) {
            val run = runs.optJSONObject(i) ?: continue
            if (run.optString("name") != WORKFLOW_NAME) continue
            if (run.optString("created_at") < afterIso) continue
            if (run.optString("status") == "completed") return run
        }
        return null
    }

    fun downloadApk(token: String, runId: Long, destDir: File): File? {
        val artifactsResp = request(token, "$API/repos/$OWNER/$REPO/actions/runs/$runId/artifacts")
            ?: return null
        val artifacts = JSONObject(artifactsResp).optJSONArray("artifacts") ?: return null
        if (artifacts.length() == 0) {
            Log.w(TAG, "no artifacts for run $runId")
            return null
        }
        val artifact = artifacts.optJSONObject(0) ?: return null
        val zipUrl = artifact.optString("archive_download_url")
            ?: return null

        val zipTemp = File(destDir, "artifact.zip")
        val apkOut = File(destDir, "DoNothingVPN-apk.apk")
        try {
            downloadToFile(token, zipUrl, zipTemp)
            ZipInputStream(zipTemp.inputStream().buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name.endsWith(".apk")) {
                        FileOutputStream(apkOut).use { out -> zip.copyTo(out) }
                        Log.i(TAG, "extracted ${entry.name} -> $apkOut")
                        return apkOut
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            Log.w(TAG, "no apk inside artifact zip")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "download/extract failed", e)
            zipTemp.delete()
            apkOut.delete()
            return null
        }
    }

    private fun downloadToFile(token: String, url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 120000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("User-Agent", "DoNothingVPN")
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun request(token: String, url: String, method: String = "GET", body: String? = null): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "DoNothingVPN")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                Log.w(TAG, "$method $url -> $code: $resp")
                null
            } else {
                resp
            }
        } catch (e: Exception) {
            Log.e(TAG, "$method $url failed", e)
            null
        }
    }

    private fun toIsoUtc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}