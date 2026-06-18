package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.Clipboard

@Dao
interface ClipboardDao : BaseDao<Clipboard> {

    /** 查询未删除的剪切板（用于显示） */
    @Query("select * from clipboard WHERE deletedAt IS NULL ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Clipboard>

    /** 查询全部（含删除标记），用于同步 */
    @Query("select * from clipboard ORDER BY isKeep DESC, time DESC")
    fun getAllForSync(): List<Clipboard>

    /** 软删除 */
    @Query("update clipboard set deletedAt = :now, lastModifiedAt = :now where content = :content")
    fun softDeleteByContent(content: String, now: Long = System.currentTimeMillis())

    /** 软删除未置顶的全部 */
    @Query("update clipboard set deletedAt = :now, lastModifiedAt = :now where isKeep = 0")
    fun softDeleteAllExceptKeep(now: Long = System.currentTimeMillis())

    /** 软删除最旧的 N 条未置顶 */
    @Query("UPDATE clipboard SET deletedAt = :now, lastModifiedAt = :now WHERE content IN (SELECT content FROM clipboard WHERE isKeep = 0 AND deletedAt IS NULL ORDER BY time ASC LIMIT :limit)")
    fun softDeleteOldest(limit: Int, now: Long = System.currentTimeMillis())

    /** 物理删除过期 tombstone */
    @Query("delete from clipboard where deletedAt IS NOT NULL AND deletedAt < :threshold")
    fun purgeDeletedBefore(threshold: Long)

    /** 硬删除（保留给 GC 使用） */
    @Query("delete from clipboard where content = :content")
    fun deleteByContent(content: String)

    @Query("delete from clipboard")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM clipboard WHERE deletedAt IS NULL")
    fun getCount(): Int

    /** 旧版硬删最旧 N 条（保留兼容，但同步改用软删） */
    @Query("DELETE FROM clipboard WHERE content IN ( SELECT content FROM clipboard ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(overflow: Int)

    /** 旧版硬删除全部非置顶 */
    @Query("DELETE FROM clipboard WHERE isKeep = 0")
    fun deleteAllExceptKeep()
}