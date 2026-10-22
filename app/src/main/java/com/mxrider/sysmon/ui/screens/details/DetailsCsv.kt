package com.mxrider.sysmon.ui.screens.details

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.mxrider.sysmon.R
import com.mxrider.sysmon.core.AppEntry
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Details-table CSV export, offered through the system share sheet. The file
 * is raw data, not display text: bytes stay bytes and timestamps are ISO-8601
 * UTC, because the audience is a spreadsheet, not a screen. Everything in it
 * is already visible in the table — exporting reveals nothing new, it only
 * saves retyping.
 */
internal object DetailsCsv {

    /**
     * Writes [rows] to a cache-dir CSV and returns a share-sheet chooser for
     * it, or null on I/O failure. Runs its file work on [Dispatchers.IO].
     */
    suspend fun buildShareIntent(context: Context, rows: List<AppEntry>): Intent? {
        val app = context.applicationContext
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(app.cacheDir, EXPORT_DIR).apply { mkdirs() }
                // One export at a time: stale files would otherwise pile up in
                // cache until the system trims it.
                dir.listFiles()?.forEach { it.delete() }
                val stamp = FILE_STAMP.format(Instant.now())
                File(dir, "sysmon-apps-$stamp.csv").apply {
                    bufferedWriter().use { out ->
                        out.appendLine(HEADER)
                        for (row in rows) {
                            out.appendLine(row.toCsvLine())
                        }
                    }
                }
            } catch (_: Exception) {
                null
            }
        } ?: return null

        val uri = try {
            FileProvider.getUriForFile(app, authorityFor(app), file)
        } catch (_: Exception) {
            return null
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, app.getString(R.string.details_export_chooser))
    }

    /**
     * Derived from the running package, matching the manifest's
     * `${applicationId}.fileprovider`. A hardcoded authority collides between
     * build variants — the OS refuses to install a second package declaring
     * one that already exists.
     */
    private fun authorityFor(context: Context): String = "${context.packageName}.fileprovider"

    private const val EXPORT_DIR = "exports"

    private const val HEADER =
        "label,package,version_name,version_code,target_sdk,min_sdk," +
            "apk_size_bytes,installed_utc,updated_utc,installer,enabled,system,uid"

    private val FILE_STAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US).withZone(ZoneOffset.UTC)

    private val ISO_UTC: DateTimeFormatter =
        DateTimeFormatter.ISO_INSTANT

    private fun AppEntry.toCsvLine(): String = listOf(
        text(label),
        packageName,
        text(versionName.orEmpty()),
        versionCode.toString(),
        targetSdk.toString(),
        minSdk.toString(),
        apkSizeBytes.toString(),
        isoOrEmpty(installTime),
        isoOrEmpty(updateTime),
        text(installerPackage.orEmpty()),
        enabled.toString(),
        isSystem.toString(),
        uid.toString(),
    ).joinToString(",") { escape(it) }

    private fun isoOrEmpty(epochMillis: Long): String =
        if (epochMillis <= 0) "" else ISO_UTC.format(Instant.ofEpochMilli(epochMillis))

    /**
     * Free text an installed app controls (its label, versionName). A leading
     * = + - @ tab or CR makes spreadsheets evaluate the cell as a formula, so
     * such a value gets a leading apostrophe and stays plain text.
     */
    private fun text(field: String): String =
        if (field.isNotEmpty() && field[0] in FORMULA_LEADS) "'$field" else field

    private val FORMULA_LEADS = charArrayOf('=', '+', '-', '@', '\t', '\r')

    /** RFC 4180: quote fields containing commas, quotes or line breaks. */
    private fun escape(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + field.replace("\"", "\"\"") + "\""
        } else {
            field
        }
}
