package com.yuyan.imemodule.manager

import android.content.SharedPreferences
import com.yuyan.imemodule.BuildConfig
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.Launcher
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Clipboard
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.database.entry.SideSymbol
import com.yuyan.imemodule.database.entry.SkbFun
import com.yuyan.imemodule.database.entry.UsedSymbol
import com.yuyan.imemodule.utils.errorRuntime
import com.yuyan.imemodule.utils.extract
import com.yuyan.imemodule.utils.versionCodeCompat
import com.yuyan.imemodule.utils.withTempDir
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 用户数据管理器
 *
 * - 手动导出/导入使用 ZIP 打包原始文件（shared_prefs / databases / external）
 * - WebDAV 同步使用独立序列化方法 serializeSyncJson / deserializeSyncJson
 */
object UserDataManager {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Serializable
    data class Metadata(
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val exportTime: Long = System.currentTimeMillis()
    )

    @Serializable
    data class PrefEntry(
        val value: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    @Serializable
    data class SettingsData(
        val entries: Map<String, PrefEntry> = emptyMap(),
        val exportTime: Long = System.currentTimeMillis()
    )

    private val sharedPrefs: SharedPreferences by lazy {
        Launcher.instance.context.getSharedPreferences(
            Launcher.instance.context.packageName + "_preferences",
            android.content.Context.MODE_PRIVATE
        )
    }

    private val db: DataBaseKT by lazy { DataBaseKT.instance }

    private val sharedPrefsDir = File(Launcher.instance.context.applicationInfo.dataDir, "shared_prefs")
    private val dataBasesDir = File(Launcher.instance.context.applicationInfo.dataDir, "databases")
    private val externalDir = Launcher.instance.context.getExternalFilesDir(null)!!

    // ─────────── 手动导出/导入（原始文件格式，兼容原版） ───────────

    @OptIn(ExperimentalSerializationApi::class)
    fun export(dest: OutputStream, timestamp: Long = System.currentTimeMillis()) = runCatching {
        ZipOutputStream(dest.buffered()).use { zipStream ->
            fun writeFileTree(srcDir: File, destPrefix: String) {
                zipStream.putNextEntry(ZipEntry("$destPrefix/"))
                srcDir.walkTopDown().forEach { f ->
                    val related = f.relativeTo(srcDir)
                    if (related.path != "") {
                        if (f.isDirectory) {
                            zipStream.putNextEntry(ZipEntry("$destPrefix/${related.path}/"))
                        } else if (f.isFile) {
                            zipStream.putNextEntry(ZipEntry("$destPrefix/${related.path}"))
                            f.inputStream().use { it.copyTo(zipStream) }
                        }
                    }
                }
            }
            writeFileTree(sharedPrefsDir, "shared_prefs")
            writeFileTree(dataBasesDir, "databases")
            writeFileTree(externalDir, "external")
            zipStream.putNextEntry(ZipEntry("metadata.json"))
            val pkgInfo = Launcher.instance.context.packageManager.getPackageInfo(
                Launcher.instance.context.packageName, 0
            )
            val metadata = Metadata(
                pkgInfo.packageName,
                pkgInfo.versionCodeCompat,
                BuildConfig.versionName,
                timestamp
            )
            json.encodeToStream(metadata, zipStream)
            zipStream.closeEntry()
        }
    }

    fun import(src: InputStream) = runCatching {
        ZipInputStream(src).use { zipStream ->
            withTempDir { tempDir ->
                val metadataFile = zipStream.extract(tempDir).find { it.name == "metadata.json" }
                    ?: errorRuntime(R.string.exception_user_data_metadata)
                requireNotNull(metadataFile)
                val metadata = json.decodeFromString<Metadata>(metadataFile.readText())

                fun copyDir(source: File, target: File) {
                    if (source.exists() && source.isDirectory) {
                        source.copyRecursively(target, overwrite = true)
                    }
                }
                // 导入前先清空目标目录
                dataBasesDir.deleteRecursively()
                externalDir.deleteRecursively()
                // shared_prefs 使用 editor.clear() 避免引用冲突
                // 必须用 commit()（同步），apply() 会异步排队写入磁盘，
                // 导致后续 copyDir 写入的备份数据被空的 preferences 覆盖
                sharedPrefs.edit().clear().commit()

                copyDir(File(tempDir, "shared_prefs"), sharedPrefsDir)
                copyDir(File(tempDir, "databases"), dataBasesDir)
                copyDir(File(tempDir, "external"), externalDir)
                metadata
            }
        }
    }

    /** 返回字节数组形式的完整原始 ZIP（用于 WebDAV 备份上传） */
    fun exportToZipBytes(): ByteArray {
        val baos = ByteArrayOutputStream()
        export(baos).getOrThrow()
        return baos.toByteArray()
    }

    /** 从字节数组恢复原始 ZIP 数据（用于 WebDAV 备份恢复） */
    fun restoreFromZipBytes(data: ByteArray) {
        import(ByteArrayInputStream(data)).getOrThrow()
    }

    // ─────────── 读当前数据（用于 WebDAV 同步序列化） ───────────

    private fun readSettings(): SettingsData {
        val entries = sharedPrefs.all.mapValues { (_, v) ->
            PrefEntry(v.toString(), System.currentTimeMillis())
        }
        return SettingsData(entries)
    }

    private fun readPhrases(): List<Phrase> = db.phraseDao().getAll()
    private fun readClipboard(): List<Clipboard> = db.clipboardDao().getAll()
    private fun readSideSymbols(): List<SideSymbol> = db.sideSymbolDao().getAllSideSymbolPinyin()
    private fun readSkbFuns(): List<SkbFun> {
        val menu = db.skbFunDao().getAllMenu()
        val bar = db.skbFunDao().getALlBarMenu()
        return menu + bar
    }
    private fun readUsedSymbols(): List<UsedSymbol> = db.usedSymbolDao().getAllUsedSymbol()

    private fun readMetadata(): Metadata {
        val pkgInfo = Launcher.instance.context.packageManager.getPackageInfo(
            Launcher.instance.context.packageName, 0
        )
        return Metadata(
            pkgInfo.packageName,
            pkgInfo.versionCodeCompat,
            BuildConfig.versionName,
            System.currentTimeMillis()
        )
    }

    // ─────────── 全量序列化工具（JSON 方式，用于 WebDAV） ───────────

    @Serializable
    private data class SyncPackage(
        val settings: SettingsData? = null,
        val phrases: List<Phrase>? = null,
        val clipboard: List<Clipboard>? = null,
        val sideSymbols: List<SideSymbol>? = null,
        val skbFuns: List<SkbFun>? = null,
        val usedSymbols: List<UsedSymbol>? = null,
        val metadata: Metadata? = null
    )

    /** 所有数据导出为单个 JSON（用于 WebDAV 上传） */
    fun serializeSyncJson(): String {
        return json.encodeToString(
            SyncPackage(
                settings = readSettings(),
                phrases = readPhrases(),
                clipboard = readClipboard(),
                sideSymbols = readSideSymbols(),
                skbFuns = readSkbFuns(),
                usedSymbols = readUsedSymbols(),
                metadata = readMetadata()
            )
        )
    }

    /** 从 JSON 恢复数据（merge=true 增量合并） */
    @OptIn(ExperimentalSerializationApi::class)
    fun deserializeSyncJson(jsonStr: String, merge: Boolean = true) {
        val pkg = json.decodeFromStream<SyncPackage>(
            ByteArrayInputStream(jsonStr.toByteArray(Charsets.UTF_8))
        )
        pkg.settings?.let { replaceSettings(it) }
        if (merge) {
            pkg.phrases?.let { mergePhrases(it) }
            pkg.clipboard?.let { mergeClipboard(it) }
            pkg.sideSymbols?.let { mergeSideSymbols(it) }
            pkg.skbFuns?.let { mergeSkbFuns(it) }
            pkg.usedSymbols?.let { mergeUsedSymbols(it) }
        } else {
            pkg.phrases?.let { replacePhrases(it) }
            pkg.clipboard?.let { replaceClipboard(it) }
            pkg.sideSymbols?.let { replaceSideSymbols(it) }
            pkg.skbFuns?.let { replaceSkbFuns(it) }
            pkg.usedSymbols?.let { replaceUsedSymbols(it) }
        }
    }

    // ─────────── 设置写入 ───────────

    fun replaceSettings(remote: SettingsData) {
        val editor = sharedPrefs.edit()
        editor.clear()
        for ((key, entry) in remote.entries) {
            putTypedValue(editor, key, entry.value)
        }
        editor.apply()
    }

    // ─────────── 数据库写入 ───────────

    fun replacePhrases(items: List<Phrase>) {
        db.phraseDao().deleteAll()
        if (items.isNotEmpty()) db.phraseDao().insertAll(items)
    }

    fun replaceClipboard(items: List<Clipboard>) {
        db.clipboardDao().deleteAll()
        if (items.isNotEmpty()) db.clipboardDao().insertAll(items)
    }

    fun replaceSideSymbols(items: List<SideSymbol>) {
        db.sideSymbolDao().deleteAll("pinyin")
        if (items.isNotEmpty()) db.sideSymbolDao().insertAll(items)
    }

    fun replaceSkbFuns(items: List<SkbFun>) {
        db.skbFunDao().deleteAll()
        if (items.isNotEmpty()) db.skbFunDao().insertAll(items)
    }

    fun replaceUsedSymbols(items: List<UsedSymbol>) {
        db.usedSymbolDao().deleteAll()
        if (items.isNotEmpty()) db.usedSymbolDao().insertAll(items)
    }

    // ─────────── 合并写入 ───────────

    private fun <T : Any> mergeLists(local: List<T>, remote: List<T>, keyExtractor: (T) -> Any): List<T> {
        val map = linkedMapOf<Any, T>()
        for (item in remote) map[keyExtractor(item)] = item
        for (item in local) {
            val key = keyExtractor(item)
            if (!map.containsKey(key)) map[key] = item
        }
        return map.values.toList()
    }

    fun mergePhrases(remote: List<Phrase>) {
        val local = db.phraseDao().getAll()
        val merged = mergeLists(local, remote) { it.content }
        db.phraseDao().deleteAll()
        if (merged.isNotEmpty()) db.phraseDao().insertAll(merged)
    }

    fun mergeClipboard(remote: List<Clipboard>) {
        val local = db.clipboardDao().getAll()
        val merged = mergeLists(local, remote) { it.content }
        db.clipboardDao().deleteAll()
        if (merged.isNotEmpty()) db.clipboardDao().insertAll(merged)
    }

    fun mergeSideSymbols(remote: List<SideSymbol>) {
        val local = db.sideSymbolDao().getAllSideSymbolPinyin()
        val merged = mergeLists(local, remote) { it.symbolKey }
        db.sideSymbolDao().deleteAll("pinyin")
        if (merged.isNotEmpty()) db.sideSymbolDao().insertAll(merged)
    }

    fun mergeSkbFuns(remote: List<SkbFun>) {
        val local = db.skbFunDao().getAllMenu() + db.skbFunDao().getALlBarMenu()
        val merged = mergeLists(local, remote) { "${it.name}_${it.isKeep}" }
        db.skbFunDao().deleteAll()
        if (merged.isNotEmpty()) db.skbFunDao().insertAll(merged)
    }

    fun mergeUsedSymbols(remote: List<UsedSymbol>) {
        val local = db.usedSymbolDao().getAllUsedSymbol()
        val merged = mergeLists(local, remote) { it.symbol }
        for (item in local) db.usedSymbolDao().delete(item)
        if (merged.isNotEmpty()) db.usedSymbolDao().insertAll(merged)
    }

    // ─────────── 辅助 ───────────

    private fun putTypedValue(editor: SharedPreferences.Editor, key: String, value: String) {
        try {
            when {
                value.toBooleanStrictOrNull() != null -> editor.putBoolean(key, value.toBooleanStrict())
                value.toIntOrNull() != null -> editor.putInt(key, value.toInt())
                value.toLongOrNull() != null -> editor.putLong(key, value.toLong())
                value.toFloatOrNull() != null -> editor.putFloat(key, value.toFloat())
                else -> editor.putString(key, value)
            }
        } catch (_: Exception) {
            editor.putString(key, value)
        }
    }
}
