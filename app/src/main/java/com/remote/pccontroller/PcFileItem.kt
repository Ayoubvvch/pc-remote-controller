package com.remote.pccontroller

data class PcFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val sizeStr: String = "",
    val sizeBytes: Long = 0,
    val ext: String = ""
)
