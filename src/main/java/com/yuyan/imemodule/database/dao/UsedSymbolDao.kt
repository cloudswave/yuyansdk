package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.UsedSymbol

@Dao
interface UsedSymbolDao : BaseDao<UsedSymbol> {

    /** 查询未删除的已用符号 */
    @Query("select * from usedSymbol where type = 'symbol' AND deletedAt IS NULL ORDER BY time DESC")
    fun getAllUsedSymbol(): List<UsedSymbol>

    /** 查询全部已用符号（含删除标记），用于同步 */
    @Query("select * from usedSymbol where type = 'symbol' ORDER BY time DESC")
    fun getAllUsedSymbolForSync(): List<UsedSymbol>

    /** 查询未删除的已用 emoji */
    @Query("select * from usedSymbol where type = 'emoji' AND deletedAt IS NULL ORDER BY time DESC")
    fun getAllSymbolEmoji(): List<UsedSymbol>

    /** 查询全部已用 emoji（含删除标记），用于同步 */
    @Query("select * from usedSymbol where type = 'emoji' ORDER BY time DESC")
    fun getAllSymbolEmojiForSync(): List<UsedSymbol>

    @Query("SELECT COUNT(*) FROM usedSymbol where type = :type AND deletedAt IS NULL")
    fun getCount(type: String): Int

    /** 软删除最旧的 N 条 */
    @Query("UPDATE usedSymbol SET deletedAt = :now, lastModifiedAt = :now WHERE symbol IN (SELECT symbol FROM usedSymbol WHERE type = :type AND deletedAt IS NULL ORDER BY time ASC LIMIT :limit)")
    fun softDeleteOldest(type: String, limit: Int, now: Long = System.currentTimeMillis())

    /** 物理删除过期 tombstone */
    @Query("delete from usedSymbol where deletedAt IS NOT NULL AND deletedAt < :threshold")
    fun purgeDeletedBefore(threshold: Long)

    /** 旧版硬删最旧 N 条（保留兼容，调用方已改用软删） */
    @Query("DELETE FROM usedSymbol WHERE symbol IN ( SELECT symbol FROM usedSymbol WHERE type = :type ORDER BY time ASC LIMIT :overflow)")
    fun deleteOldest(type: String, overflow: Int)

    @Query("DELETE FROM usedSymbol")
    fun deleteAll()
}