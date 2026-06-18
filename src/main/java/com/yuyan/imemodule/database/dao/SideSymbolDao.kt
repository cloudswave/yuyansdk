package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.SideSymbol

@Dao
interface SideSymbolDao : BaseDao<SideSymbol> {

    @Query("select * from side_symbol where symbolKey = :key AND type = :type")
    fun getByKey(key: String, type: String = "pinyin"): SideSymbol?

    /** 查询未删除的侧边符号（用于显示） */
    @Query("select * from side_symbol where type = 'number' AND deletedAt IS NULL")
    fun getAllSideSymbolNumber(): List<SideSymbol>

    /** 查询全部（含删除标记），用于同步 */
    @Query("select * from side_symbol where type = 'number'")
    fun getAllSideSymbolNumberForSync(): List<SideSymbol>

    /** 查询未删除的拼音侧边符号 */
    @Query("select * from side_symbol where type = 'pinyin' AND deletedAt IS NULL")
    fun getAllSideSymbolPinyin(): List<SideSymbol>

    /** 查询全部拼音侧边符号（含删除标记），用于同步 */
    @Query("select * from side_symbol where type = 'pinyin'")
    fun getAllSideSymbolPinyinForSync(): List<SideSymbol>

    /** 软删除 */
    @Query("update side_symbol set deletedAt = :now, lastModifiedAt = :now where symbolKey = :key AND type = :type")
    fun softDeleteByKey(key: String, type: String = "pinyin", now: Long = System.currentTimeMillis())

    /** 软删除全部 */
    @Query("update side_symbol set deletedAt = :now, lastModifiedAt = :now where type = :type")
    fun softDeleteAll(type: String = "pinyin", now: Long = System.currentTimeMillis())

    /** 物理删除过期 tombstone */
    @Query("delete from side_symbol where deletedAt IS NOT NULL AND deletedAt < :threshold")
    fun purgeDeletedBefore(threshold: Long)

    /** 硬删除 */
    @Query("delete from side_symbol where symbolKey = :key AND type = :type")
    fun deleteByKey(key: String, type: String = "pinyin")

    @Query("delete from side_symbol where type = :type")
    fun deleteAll(type: String = "pinyin")

    @Query("update side_symbol set symbolValue =:value where symbolKey =:key AND type = :type")
    fun updateSymbol(key: String, value: String, type: String = "pinyin")
}