package com.yuyan.imemodule.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.yuyan.imemodule.database.BaseDao
import com.yuyan.imemodule.database.entry.SkbFun

@Dao
interface SkbFunDao : BaseDao<SkbFun> {

    /** 查询未删除的菜单 */
    @Query("select * from skbfun where isKeep = 0 AND deletedAt IS NULL ORDER BY position ASC")
    fun getAllMenu(): List<SkbFun>

    /** 查询全部菜单（含删除标记），用于同步 */
    @Query("select * from skbfun where isKeep = 0 ORDER BY position ASC")
    fun getAllMenuForSync(): List<SkbFun>

    /** 查询未删除的工具栏 */
    @Query("select * from skbfun where isKeep = 1 AND deletedAt IS NULL")
    fun getALlBarMenu(): List<SkbFun>

    /** 查询全部工具栏（含删除标记），用于同步 */
    @Query("select * from skbfun where isKeep = 1")
    fun getALlBarMenuForSync(): List<SkbFun>

    @Query("select * from skbfun where name = :name AND isKeep = 1")
    fun getBarMenu(name: String): SkbFun?

    /** 软删除全部 */
    @Query("update skbfun set deletedAt = :now, lastModifiedAt = :now")
    fun softDeleteAll(now: Long = System.currentTimeMillis())

    /** 物理删除过期 tombstone */
    @Query("delete from skbfun where deletedAt IS NOT NULL AND deletedAt < :threshold")
    fun purgeDeletedBefore(threshold: Long)

    @Query("delete from skbfun")
    fun deleteAll()
}