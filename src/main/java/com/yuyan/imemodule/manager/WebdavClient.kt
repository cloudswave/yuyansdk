package com.yuyan.imemodule.manager

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 轻量级 WebDAV 客户端 — 基于 OkHttp 实现
 * 支持 Basic 认证、PUT 上传、GET 下载、PROPFIND 检查、MKCOL 创建目录
 */
class WebdavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    data class WebdavException(
        val errorMessage: String,
        val errorCause: Throwable? = null
    ) : Exception(errorMessage, errorCause)

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val credential = Credentials.basic(username, password)

    private val jsonMediaType = "application/json".toMediaType()
    private val octetMediaType = "application/octet-stream".toMediaType()

    /** 规范化 URL：确保不以 / 结尾，加上基础路径 */
    private fun normalizeUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        val cleanPath = path.trimStart('/')
        return "$base/$cleanPath"
    }

    /** 检查连接是否可用 */
    fun checkConnection(): Result<Unit> = runCatching {
        val url = normalizeUrl("")
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credential)
            .header("Depth", "0")
            .build()
        val response = client.newCall(request).execute()
        val code = response.code
        response.close()
        when (code) {
            207 -> { /* 正常 */ }
            404 -> {
                // 路径还不存在，但服务器本身是可达的
                throw WebdavException(errorMessage = "数据目录尚不存在（上传数据时将自动创建）")
            }
            401 -> throw WebdavException(errorMessage = "认证失败，请检查用户名和密码")
            403 -> throw WebdavException(errorMessage = "无权限访问，请检查服务器配置")
            405 -> throw WebdavException(errorMessage = "服务器不支持 WebDAV")
            else -> throw WebdavException(errorMessage = "连接失败 (HTTP $code)")
        }
    }.recoverCatching { e ->
        throw mapNetworkError(e)
    }

    /** 确保远程目录存在（递归创建 MKCOL） */
    fun ensureDirectory(remoteDir: String): Result<Unit> = runCatching {
        val dir = remoteDir.trim('/')
        if (dir.isEmpty()) return Result.success(Unit)

        // 逐级创建
        val parts = dir.split("/")
        var current = ""
        for (part in parts) {
            if (part.isEmpty()) continue
            current += "/$part"
            val url = normalizeUrl(current)
            val request = Request.Builder()
                .url(url)
                .method("MKCOL", null)
                .header("Authorization", credential)
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            // 405 表示已存在，也是成功
            if (code != 201 && code != 405) {
                throw WebdavException(errorMessage = "创建目录失败 (HTTP $code)")
            }
        }
    }.recoverCatching { e -> throw mapNetworkError(e) }

    /** 上传文件到 WebDAV */
    fun upload(data: ByteArray, remotePath: String): Result<Unit> = runCatching {
        val url = normalizeUrl(remotePath)
        val body = data.toRequestBody(octetMediaType)
        val request = Request.Builder()
            .url(url)
            .put(body)
            .header("Authorization", credential)
            .build()
        val response = client.newCall(request).execute()
        val code = response.code
        response.close()
        if (code !in 200..204 && code != 201) {
            throw WebdavException(errorMessage = "上传失败 (HTTP $code)")
        }
    }.recoverCatching { e -> throw mapNetworkError(e) }

    /** 从 WebDAV 下载文件 */
    fun download(remotePath: String): Result<ByteArray> = runCatching {
        val url = normalizeUrl(remotePath)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", credential)
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw WebdavException(errorMessage = 
                when (code) {
                    404 -> "远程文件不存在"
                    401 -> "认证失败"
                    else -> "下载失败 (HTTP $code)"
                }
            )
        }
        response.body?.bytes() ?: throw WebdavException(errorMessage = "下载数据为空")
    }.recoverCatching { e -> throw mapNetworkError(e) }

    /** 确保 base URL 对应的远程集合目录存在（不存在则自动创建） */
    fun ensureBaseDirectory(): Result<Unit> = runCatching {
        val url = baseUrl.trimEnd('/')
        val request = Request.Builder()
            .url(url)
            .method("MKCOL", null)
            .header("Authorization", credential)
            .build()
        val response = client.newCall(request).execute()
        val code = response.code
        response.close()
        // 201=创建成功, 405=已存在/不支持MKCOL(集合已存在), 也是成功
        if (code != 201 && code != 405) {
            throw WebdavException(errorMessage = "创建远程目录失败 (HTTP $code)")
        }
    }.recoverCatching { e ->
        if (e is WebdavException) throw e
        // 其他错误（如服务器根不支持MKCOL）静默忽略——上传流程会自己失败
    }

    /** 检查远程文件是否存在（PROPFIND，兼容性优于 HEAD） */
    fun exists(remotePath: String): Result<Boolean> = runCatching {
        val url = normalizeUrl(remotePath)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credential)
            .header("Depth", "0")
            .build()
        val response = client.newCall(request).execute()
        val exists = response.isSuccessful
        response.close()
        exists
    }.recoverCatching { e ->
        if (e is WebdavException) throw e
        // 网络错误视为不存在
        false
    }

    /** 获取远程文件最后修改时间（PROPFIND Depth 0，读 Last-Modified 头） */
    fun getLastModified(remotePath: String): Result<Long> = runCatching {
        val url = normalizeUrl(remotePath)
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credential)
            .header("Depth", "0")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 404) throw WebdavException("远程文件不存在")
            throw WebdavException("获取文件信息失败 (HTTP $code)")
        }
        val lastModifiedHeader = response.header("Last-Modified")
        response.close()
        if (lastModifiedHeader != null) {
            val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("GMT")
            sdf.parse(lastModifiedHeader)?.time
                ?: throw WebdavException("无法解析 Last-Modified")
        } else {
            throw WebdavException("服务端未返回 Last-Modified")
        }
    }.recoverCatching { e -> throw mapNetworkError(e) }

    /** 删除远程文件 */
    fun delete(remotePath: String): Result<Unit> = runCatching {
        val url = normalizeUrl(remotePath)
        val request = Request.Builder()
            .url(url)
            .delete()
            .header("Authorization", credential)
            .build()
        val response = client.newCall(request).execute()
        val code = response.code
        response.close()
        if (code !in 200..299 && code != 204 && code != 404) {
            throw WebdavException(errorMessage = "删除失败 (HTTP $code)")
        }
    }.recoverCatching { e -> throw mapNetworkError(e) }

    /** 列出远程目录下所有文件名 */
    fun listFiles(remoteDir: String): Result<List<String>> = runCatching {
        val url = normalizeUrl(remoteDir.trimEnd('/'))
        val request = Request.Builder()
            .url(url)
            .method("PROPFIND", null)
            .header("Authorization", credential)
            .header("Depth", "1")
            .build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw WebdavException(errorMessage = "列出目录失败 (HTTP ${response.code})")
        }
        val body = response.body?.string() ?: ""
        response.close()

        // 简单解析 XML 提取 href 标签（兼容任意命名空间前缀）
        val files = mutableListOf<String>()
        val hrefRegex = Regex("<[a-zA-Z0-9]+:href>([^<]+)</[a-zA-Z0-9]+:href>")
        val matches = hrefRegex.findAll(body)
        for (match in matches) {
            val href = match.groupValues[1].trimEnd('/')
            val fileName = href.substringAfterLast('/')
            if (fileName.isNotEmpty()) files.add(fileName)
        }
        files
    }.recoverCatching { e -> throw mapNetworkError(e) }

    private fun mapNetworkError(e: Throwable): WebdavException {
        return when (e) {
            is WebdavException -> e
            is UnknownHostException -> WebdavException(errorMessage = "无法解析服务器地址，请检查 URL")
            is SocketTimeoutException -> WebdavException(errorMessage = "连接超时，请检查网络和服务器地址")
            is SSLException -> WebdavException(errorMessage = "SSL 连接错误，请检查是否使用 HTTPS")
            is IOException -> WebdavException(errorMessage = "网络错误: ${e.localizedMessage ?: "未知错误"}")
            else -> WebdavException(errorMessage = "未知错误: ${e.localizedMessage ?: e.message ?: "未知"}")
        }
    }
}
