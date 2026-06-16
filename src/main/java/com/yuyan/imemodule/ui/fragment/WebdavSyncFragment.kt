package com.yuyan.imemodule.ui.fragment

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import com.yuyan.imemodule.R
import com.yuyan.imemodule.manager.SyncManager
import com.yuyan.imemodule.manager.WebdavClient
import com.yuyan.imemodule.prefs.WebdavPrefs
import com.yuyan.imemodule.ui.fragment.base.CsPreferenceFragment
import com.yuyan.imemodule.utils.addCategory
import com.yuyan.imemodule.utils.addPreference
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WebdavSyncFragment : CsPreferenceFragment() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val ctx = requireContext()
        preferenceScreen = preferenceManager.createPreferenceScreen(ctx).apply {

            addCategory(R.string.webdav_config) {
                isIconSpaceReserved = false

                EditTextPreference(ctx).apply {
                    key = "webdav_url"
                    setTitle(R.string.webdav_server_url)
                    setDialogTitle(R.string.webdav_server_url)
                    summary = WebdavPrefs.url.ifEmpty { "未设置" }
                    // 同步持久化值，确保弹窗预填正确（备份恢复后默认 prefs 可能不含此键）
                    text = WebdavPrefs.url
                    setOnPreferenceChangeListener { _, newValue ->
                        val url = newValue?.toString()?.trim() ?: ""
                        WebdavPrefs.url = url
                        summary = url.ifEmpty { "未设置" }
                        true
                    }
                }.let { addPreference(it) }

                EditTextPreference(ctx).apply {
                    key = "webdav_username"
                    setTitle(R.string.webdav_username)
                    setDialogTitle(R.string.webdav_username)
                    summary = WebdavPrefs.username.ifEmpty { "未设置" }
                    text = WebdavPrefs.username
                    setOnPreferenceChangeListener { _, newValue ->
                        val name = newValue?.toString()?.trim() ?: ""
                        WebdavPrefs.username = name
                        summary = name.ifEmpty { "未设置" }
                        true
                    }
                }.let { addPreference(it) }

                EditTextPreference(ctx).apply {
                    key = "webdav_password"
                    setTitle(R.string.webdav_password)
                    setDialogTitle(R.string.webdav_password)
                    summary = if (WebdavPrefs.password.isNotEmpty()) "••••••••" else "未设置"
                    text = WebdavPrefs.password
                    setOnPreferenceChangeListener { _, newValue ->
                        val pw = newValue?.toString() ?: ""
                        WebdavPrefs.password = pw
                        summary = if (pw.isNotEmpty()) "••••••••" else "未设置"
                        true
                    }
                }.let { addPreference(it) }

                addPreference(R.string.webdav_test_connection, onClick = {
                    testConnection()
                })
            }

            addCategory(R.string.webdav_sync_actions) {
                isIconSpaceReserved = false

                SwitchPreferenceCompat(ctx).apply {
                    key = "webdav_auto_sync"
                    setTitle(R.string.webdav_auto_sync)
                    summaryOn = "已开启 — 键盘弹出时自动同步，数据变更后防抖上传"
                    summaryOff = "关闭"
                    isChecked = WebdavPrefs.autoSync
                    setOnPreferenceChangeListener { _, newValue ->
                        val enabled = newValue == true
                        WebdavPrefs.autoSync = enabled
                        true
                    }
                }.let { addPreference(it) }

                addPreference(R.string.webdav_upload, onClick = {
                    startSync(isUpload = true)
                })

                addPreference(R.string.webdav_download_merge, R.string.webdav_download_merge_summary, onClick = {
                    startSync(isUpload = false)
                })
            }

            // ─── 全量备份与恢复 ─────────────────────────────────
            addCategory(R.string.webdav_backup_actions) {
                isIconSpaceReserved = false

                addPreference(R.string.webdav_backup_full, R.string.webdav_backup_full_summary, onClick = {
                    startBackup()
                })

                addPreference(R.string.webdav_restore, R.string.webdav_restore_summary, onClick = {
                    startRestore()
                })
            }

            addCategory(R.string.webdav_info) {
                isIconSpaceReserved = false
                addPreference(getString(R.string.webdav_last_sync), formatLastSync(WebdavPrefs.lastSyncTime))
            }
        }
    }

    private fun formatLastSync(timestamp: Long): String {
        if (timestamp == 0L) return "从未同步"
        return TimeUtils.iso8601UTCDateTime(timestamp) + " (UTC)"
    }

    // ─── 测试连接 ──────────────────────────────────────────

    private fun testConnection() {
        if (!WebdavPrefs.isConfigured()) {
            Toast.makeText(requireContext(), R.string.webdav_config_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ctx = requireContext()
            val pref = findPreference<Preference>("webdav_test_connection")
            pref?.isEnabled = false
            pref?.summary = "测试中…"
            val client = WebdavClient(WebdavPrefs.url, WebdavPrefs.username, WebdavPrefs.password)
            val result = withContext(Dispatchers.IO) { client.checkConnection() }
            result.onSuccess {
                Toast.makeText(ctx, R.string.webdav_connection_success, Toast.LENGTH_SHORT).show()
                pref?.summary = "连接成功 ✓"
            }.onFailure { e ->
                Toast.makeText(ctx, "连接失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
                pref?.summary = "连接失败 ✗"
            }
            pref?.isEnabled = true
            delay(3000)
            pref?.summary = ""
        }
    }

    // ─── 增量同步 ──────────────────────────────────────────

    private fun startSync(isUpload: Boolean) {
        if (!WebdavPrefs.isConfigured()) {
            Toast.makeText(requireContext(), R.string.webdav_config_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ctx = requireContext()
            val client = WebdavClient(WebdavPrefs.url, WebdavPrefs.username, WebdavPrefs.password)
            val actionKey = if (isUpload) "webdav_upload" else "webdav_download_merge"
            val actionPref = findPreference<Preference>(actionKey)
            actionPref?.isEnabled = false
            actionPref?.summary = "同步中…"

            if (isUpload) {
                showDialog()
                val result = withContext(Dispatchers.IO) {
                    SyncManager.upload(client) { progress ->
                        launch(Dispatchers.Main) { actionPref?.summary = progress }
                    }
                }
                handleSyncResult(result, ctx, actionPref, isUpload = true)
            } else {
                // 先列出历史版本供用户选择
                showDialog()
                val listResult = withContext(Dispatchers.IO) {
                    SyncManager.listHistoryJsons(client)
                }
                listResult.onSuccess { jsons ->
                    if (jsons.isEmpty()) {
                        Toast.makeText(ctx, "服务器上尚无备份数据", Toast.LENGTH_SHORT).show()
                        actionPref?.isEnabled = true
                        actionPref?.summary = ""
                        hideDialog()
                        return@launch
                    }
                    val displayNames = jsons.map { SyncManager.historyDisplayName(it) }.toTypedArray()
                    hideDialog()
                    AlertDialog.Builder(ctx)
                        .setTitle("选择同步来源")
                        .setItems(displayNames) { _, which ->
                            val selectedFile = jsons[which]
                            lifecycleScope.launch {
                                showDialog()
                                val result = withContext(Dispatchers.IO) {
                                    SyncManager.downloadAndMerge(client, fileName = selectedFile) { progress ->
                                        launch(Dispatchers.Main) { actionPref?.summary = progress }
                                    }
                                }
                                handleSyncResult(result, ctx, actionPref, isUpload = false)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }.onFailure { e ->
                    hideDialog()
                    Toast.makeText(ctx, "扫描失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
                    actionPref?.isEnabled = true
                    actionPref?.summary = ""
                }
            }
        }
    }

    /** 处理同步结果，统一更新 UI */
    private fun handleSyncResult(
        result: Result<Unit>,
        ctx: android.content.Context,
        actionPref: Preference?,
        isUpload: Boolean
    ) {
        result.onSuccess {
            WebdavPrefs.lastSyncTime = System.currentTimeMillis()
            Toast.makeText(ctx, if (isUpload) R.string.webdav_upload_success else R.string.webdav_download_success, Toast.LENGTH_SHORT).show()
            actionPref?.summary = if (isUpload) "上传成功 ✓" else "同步完成 ✓"
            refreshLastSyncTime()
            if (!isUpload) {
                Toast.makeText(ctx, R.string.user_data_imported, Toast.LENGTH_LONG).show()
            }
        }.onFailure { e ->
            actionPref?.summary = "同步失败 ✗"
            Toast.makeText(ctx, "${if (isUpload) "上传" else "下载"}失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
        }
        actionPref?.isEnabled = true
        hideDialog()
    }

    // ─── 全量备份 ──────────────────────────────────────────

    private fun startBackup() {
        if (!WebdavPrefs.isConfigured()) {
            Toast.makeText(requireContext(), R.string.webdav_config_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ctx = requireContext()
            val client = WebdavClient(WebdavPrefs.url, WebdavPrefs.username, WebdavPrefs.password)
            val pref = findPreference<Preference>("webdav_backup_full")
            pref?.isEnabled = false
            pref?.summary = "备份中…"

            showDialog()

            val result = withContext(Dispatchers.IO) {
                SyncManager.backupFull(client) { progress ->
                    launch(Dispatchers.Main) { pref?.summary = progress }
                }
            }

            result.onSuccess {
                Toast.makeText(ctx, R.string.webdav_backup_success, Toast.LENGTH_SHORT).show()
                pref?.summary = "备份成功 ✓"
            }.onFailure { e ->
                pref?.summary = "备份失败 ✗"
                Toast.makeText(ctx, "备份失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
            }
            pref?.isEnabled = true
            hideDialog()
        }
    }

    // ─── 从备份恢复 ────────────────────────────────────────

    private fun startRestore() {
        if (!WebdavPrefs.isConfigured()) {
            Toast.makeText(requireContext(), R.string.webdav_config_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val ctx = requireContext()
            val client = WebdavClient(WebdavPrefs.url, WebdavPrefs.username, WebdavPrefs.password)

            showDialog()

            val listResult = withContext(Dispatchers.IO) {
                SyncManager.listBackups(client)
            }

            hideDialog()

            listResult.onSuccess { backups ->
                if (backups.isEmpty()) {
                    Toast.makeText(ctx, R.string.webdav_no_backups, Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 构建选择列表：显示可读时间，实际用文件名
                val displayNames = backups.map { SyncManager.backupDisplayName(it) }
                val items = displayNames.toTypedArray()

                AlertDialog.Builder(ctx)
                    .setTitle(R.string.webdav_select_backup)
                    .setItems(items) { _, which ->
                        val selectedFile = backups[which]
                        confirmAndRestore(ctx, client, selectedFile)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }.onFailure { e ->
                Toast.makeText(ctx, "扫描备份失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmAndRestore(
        ctx: android.content.Context,
        client: WebdavClient,
        fileName: String
    ) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.webdav_restore)
            .setIconAttribute(android.R.attr.alertDialogIcon)
            .setMessage(R.string.webdav_backup_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                lifecycleScope.launch {
                    val pref = findPreference<Preference>("webdav_restore")
                    pref?.isEnabled = false
                    pref?.summary = "恢复中…"
                    showDialog()

                    val result = withContext(Dispatchers.IO) {
                        SyncManager.restoreBackup(client, fileName) { progress ->
                            launch(Dispatchers.Main) { pref?.summary = progress }
                        }
                    }

                    result.onSuccess {
                        hideDialog()
                        Toast.makeText(ctx, R.string.webdav_restore_success, Toast.LENGTH_SHORT).show()
                        pref?.summary = "恢复成功 ✓"
                        withContext(NonCancellable + Dispatchers.Main) {
                            delay(400L)
                            AppUtil.exit()
                        }
                        AppUtil.showRestartNotification(ctx)
                    }.onFailure { e ->
                        pref?.summary = "恢复失败 ✗"
                        hideDialog()
                        Toast.makeText(ctx, "恢复失败: ${SyncManager.friendlyError(e)}", Toast.LENGTH_LONG).show()
                    }
                    pref?.isEnabled = true
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ─── 对话框 ────────────────────────────────────────────

    private var dialog: android.app.AlertDialog? = null

    private fun showDialog() {
        dialog = android.app.AlertDialog.Builder(requireContext())
            .setTitle(R.string.loading)
            .setMessage(R.string.please_wait)
            .setCancelable(false)
            .show()
    }

    private fun hideDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun refreshLastSyncTime() {
        val lastSyncPref = findPreference<Preference>("webdav_last_sync")
        lastSyncPref?.summary = formatLastSync(WebdavPrefs.lastSyncTime)
    }
}