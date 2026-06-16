package com.yuyan.imemodule.manager

import com.yuyan.imemodule.manager.WebdavClient.WebdavException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同步管理器
 *
 * - 增量同步：单个 JSON 文件 yuyanime.json
 * - 全量备份：原始格式 ZIP，按时间戳命名 yuyanIme_backup_yyyyMMdd_HHmmss.zip
 */
object SyncManager {

    private const val REMOTE_FILE = "yuyanime.json"
    private const val HISTORY_PREFIX = "yuyanime_history_"
    private const val BACKUP_PREFIX = "yuyanIme_backup_"
    private const val MAX_HISTORY = 10

    // ─── 增量同步 ──────────────────────────────────────────

    /** 上传本地数据到 WebDAV，同时保留带时间戳的历史版本并清理旧历史 */
    fun upload(client: WebdavClient, onProgress: ((String) -> Unit)? = null): Result<Unit> = runCatching {
        onProgress?.invoke("准备数据…")
        val jsonStr = UserDataManager.serializeSyncJson()
        val data = jsonStr.toByteArray(Charsets.UTF_8)
        onProgress?.invoke("正在上传…")
        client.ensureBaseDirectory()
        // 写入实时文件
        client.upload(data, REMOTE_FILE).getOrThrow()
        // 写入历史副本
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val historyFile = "${HISTORY_PREFIX}${ts}.json"
        client.upload(data, historyFile).getOrThrow()
        // 清理旧历史，保留最新 MAX_HISTORY 份
        cleanupHistory(client)
        onProgress?.invoke("上传完成 ✓")
    }

    /** 清理远程历史版本，只保留最近的 MAX_HISTORY 份 */
    private fun cleanupHistory(client: WebdavClient) {
        val result = client.listFiles("").getOrNull() ?: return
        val historyFiles = result.filter { it.startsWith(HISTORY_PREFIX) && it.endsWith(".json") }
            .sortedDescending() // 最新在前
        if (historyFiles.size <= MAX_HISTORY) return
        // 删除多余的旧版本
        for (oldFile in historyFiles.drop(MAX_HISTORY)) {
            runCatching { /* 忽略删除失败 */ }
        }
    }

    /** 列出远程所有历史 JSON 文件（含实时文件），按时间降序 */
    fun listHistoryJsons(client: WebdavClient): Result<List<String>> = runCatching {
        val all = client.listFiles("").getOrThrow()
        // 收集所有 JSON 文件
        val jsons = mutableListOf<String>()
        // 实时文件排首位
        if (all.contains(REMOTE_FILE)) jsons.add(REMOTE_FILE)
        // 历史文件按时间降序
        val historyFiles = all.filter { it.startsWith(HISTORY_PREFIX) && it.endsWith(".json") }
            .sortedDescending()
        jsons.addAll(historyFiles)
        jsons
    }

    /** 从文件名中提取可读时间，用于显示 */
    fun historyDisplayName(fileName: String): String {
        if (fileName == REMOTE_FILE) return "最新 (yuyanime.json)"
        val ts = fileName.removePrefix(HISTORY_PREFIX).removeSuffix(".json")
        return if (ts.length == 15) {
            "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} ${ts.substring(9, 11)}:${ts.substring(11, 13)}:${ts.substring(13, 15)}"
        } else {
            fileName
        }
    }

    /** 从 WebDAV 下载指定 JSON 文件并合并到本地 */
    fun downloadAndMerge(client: WebdavClient, fileName: String = REMOTE_FILE, onProgress: ((String) -> Unit)? = null): Result<Unit> = runCatching {
        onProgress?.invoke("正在下载远端数据…")

        val data = try {
            client.download(fileName).getOrThrow()
        } catch (e: WebdavException) {
            if (e.errorMessage.contains("远程文件不存在") || e.errorMessage.contains("404")) {
                throw SyncException("服务器上尚无备份数据，请先执行「上传到 WebDAV」")
            }
            throw e
        }
        val jsonStr = String(data, Charsets.UTF_8)

        onProgress?.invoke("正在合并到本地…")
        UserDataManager.deserializeSyncJson(jsonStr, merge = true)

        onProgress?.invoke("正在回传合并结果…")
        upload(client, onProgress)

        onProgress?.invoke("同步完成 ✓ 部分设置需重启生效")
    }

    // ─── 全量备份与恢复 ──────────────────────────────────────

    /** 生成备份文件名：yuyanIme_backup_yyyyMMdd_HHmmss.zip */
    private fun backupFileName(): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "${BACKUP_PREFIX}${ts}.zip"
    }

    /** 全量备份原始数据 ZIP 到 WebDAV */
    fun backupFull(client: WebdavClient, onProgress: ((String) -> Unit)? = null): Result<Unit> = runCatching {
        onProgress?.invoke("正在打包原始数据…")
        val zipData = UserDataManager.exportToZipBytes()

        val fileName = backupFileName()
        onProgress?.invoke("正在上传 $fileName …")
        client.ensureBaseDirectory()
        client.upload(zipData, fileName).getOrThrow()
        onProgress?.invoke("备份成功 ✓ ($fileName)")
    }

    /** 列出远程所有备份文件（按修改时间降序） */
    fun listBackups(client: WebdavClient): Result<List<String>> = runCatching {
        val all = client.listFiles("").getOrThrow()
        // 只保留备份文件
        val backups = all.filter { it.startsWith(BACKUP_PREFIX) && it.endsWith(".zip") }
            .sortedDescending() // 最新在前
        backups
    }

    /** 从 WebDAV 下载指定备份 ZIP 并恢复（覆盖本地+重启） */
    fun restoreBackup(
        client: WebdavClient,
        fileName: String,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit> = runCatching {
        onProgress?.invoke("正在下载 $fileName …")
        val data = client.download(fileName).getOrThrow()

        onProgress?.invoke("正在恢复数据…")
        UserDataManager.restoreFromZipBytes(data)

        onProgress?.invoke("恢复完成 ✓ 即将重启应用")
    }

    // ─── 工具 ──────────────────────────────────────────────

    /** 从备份文件名中提取可读时间：20260615_231400 → 2026-06-15 23:14:00 */
    fun backupDisplayName(fileName: String): String {
        val ts = fileName.removePrefix(BACKUP_PREFIX).removeSuffix(".zip")
        return if (ts.length == 15) {
            // yyyyMMdd_HHmmss
            "${ts.substring(0, 4)}-${ts.substring(4, 6)}-${ts.substring(6, 8)} ${ts.substring(9, 11)}:${ts.substring(11, 13)}:${ts.substring(13, 15)}"
        } else {
            fileName
        }
    }

    class SyncException(message: String) : Exception(message)

    fun friendlyError(e: Throwable): String {
        return when (e) {
            is SyncException -> e.message ?: "同步失败"
            is WebdavException -> e.errorMessage
            is IOException -> "网络连接失败，请检查网络设置"
            else -> "同步出错: ${e.localizedMessage ?: "未知错误"}"
        }
    }
}