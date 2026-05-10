package com.orangecloud.im.models

import com.google.gson.annotations.SerializedName

/**
 * 文本消息
 */
data class TextMessage(
    @SerializedName("messageType") override val messageType: String = MessageType.TEXT.value,
    @SerializedName("sequenceNumber") override val sequenceNumber: Long = 0,
    @SerializedName("serverTimestamp") override val serverTimestamp: Long = 0,
    @SerializedName("senderInfo") override val senderInfo: SenderInfo = SenderInfo(),
    @SerializedName("groupId") override val groupId: String = "",
    @SerializedName("content") val content: String = ""
) : IMMessage()
