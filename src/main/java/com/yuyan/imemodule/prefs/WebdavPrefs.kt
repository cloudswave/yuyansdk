package com.yuyan.imemodule.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yuyan.imemodule.application.Launcher

/**
 * WebDAV 配置安全存储
 * 密码使用 EncryptedSharedPreferences 加密存储
 */
object WebdavPrefs {

    private const val PREFS_NAME = "webdav_prefs"
    private const val KEY_URL = "webdav_url"
    private const val KEY_USERNAME = "webdav_username"
    private const val KEY_PASSWORD = "webdav_password"
    private const val KEY_LAST_SYNC_TIME = "webdav_last_sync_time"
    private const val KEY_AUTO_SYNC = "webdav_auto_sync"

    private val sp: SharedPreferences by lazy {
        val context = Launcher.instance.context
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        try {
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // 签名变更或 key 不匹配导致解密失败 → 清空重建
            context.getSharedPreferences(PREFS_NAME + "_backup", Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.deleteSharedPreferences(PREFS_NAME)
            EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }
    }

    var url: String
        get() = sp.getString(KEY_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    var username: String
        get() = sp.getString(KEY_USERNAME, "") ?: ""
        set(value) = sp.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = sp.getString(KEY_PASSWORD, "") ?: ""
        set(value) = sp.edit().putString(KEY_PASSWORD, value).apply()

    var lastSyncTime: Long
        get() = sp.getLong(KEY_LAST_SYNC_TIME, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()

    var autoSync: Boolean
        get() = sp.getBoolean(KEY_AUTO_SYNC, false)
        set(value) = sp.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** 配置是否完整 */
    fun isConfigured(): Boolean = url.isNotBlank()

    /** 清空所有 WebDAV 配置 */
    fun clear() {
        sp.edit().clear().apply()
    }
}