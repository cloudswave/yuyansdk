package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "usedSymbol")
@Serializable
data class UsedSymbol(
    @PrimaryKey
    @ColumnInfo(name = "symbol")
    var symbol: String,
    @ColumnInfo(name = "type")
    val type: String = "symbol",  // symbol、emoji
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "lastModifiedAt")
    var lastModifiedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deletedAt")
    var deletedAt: Long? = null,
)
