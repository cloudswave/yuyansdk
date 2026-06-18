package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.Phrase

@Dao
interface PhraseDao : BaseDao<Phrase> {

    /** 查询未删除的常用语（用于显示） */
    @Query("select * from phrase WHERE deletedAt IS NULL ORDER BY isKeep DESC, time DESC")
    fun getAll(): List<Phrase>

    /** 查询全部（含删除标记），用于同步 */
    @Query("select * from phrase ORDER BY isKeep DESC, time DESC")
    fun getAllForSync(): List<Phrase>

    @Query("select * from phrase  where qwerty = :index or t9 = :index or lx17 = :index ORDER BY isKeep DESC, time DESC")
    fun query(index: String): List<Phrase>

    /** 软删除 */
    @Query("update phrase set deletedAt = :now, lastModifiedAt = :now where content = :content")
    fun softDeleteByContent(content: String, now: Long = System.currentTimeMillis())

    /** 软删除全部 */
    @Query("update phrase set deletedAt = :now, lastModifiedAt = :now")
    fun softDeleteAll(now: Long = System.currentTimeMillis())

    /** 物理删除过期 tombstone */
    @Query("delete from phrase where deletedAt IS NOT NULL AND deletedAt < :threshold")
    fun purgeDeletedBefore(threshold: Long)

    /** 硬删除（保留给 GC 和全量替换使用） */
    @Query("delete from phrase where content = :content")
    fun deleteByContent(content: String)

    @Query("delete from phrase")
    fun deleteAll()

    @Query("select * from phrase where content = :content")
    fun queryByContent(content: String): Phrase
}