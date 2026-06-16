package com.yuyan.imemodule.database.entry

import androidx.room.ColumnInfo
import androidx.room.Entity
import kotlinx.serialization.Serializable

@Entity(tableName = "skbfun", primaryKeys = ["name", "isKeep"])
@Serializable
data class SkbFun(
    @ColumnInfo(name = "name")
    var name: String,
    @ColumnInfo(name = "isKeep")
    var isKeep: Int = 0,
    @ColumnInfo(name = "position")
    var position: Int = 0,
)