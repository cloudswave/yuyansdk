package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "side_symbol")
@Serializable
data class SideSymbol(
    @PrimaryKey
    @ColumnInfo(name = "symbolKey")
    var symbolKey: String,
    @ColumnInfo(name = "symbolValue")
    var symbolValue: String,
    @ColumnInfo(name = "type")
    val type: String = "pinyin",
    @ColumnInfo(name = "lastModifiedAt")
    var lastModifiedAt: Long = System.currentTimeMillis(),
)