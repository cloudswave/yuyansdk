package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Entity(tableName = "clipboard")
@Serializable
data class Clipboard(
    @PrimaryKey
    @ColumnInfo(name = "content")
    var content: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "time")
    val time: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "lastModifiedAt")
    var lastModifiedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "deletedAt")
    var deletedAt: Long? = null,
)
