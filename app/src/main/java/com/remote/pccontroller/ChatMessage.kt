package com.remote.pccontroller

data class ChatMessage(
    val message: String,
    val isUser: Boolean,
    val time: String,
    val isSuccess: Boolean = true,
    val imageUrl: String? = null,
    val attachmentName: String? = null
)
