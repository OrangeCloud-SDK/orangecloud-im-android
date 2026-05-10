package com.orangecloud.im.models

import com.google.gson.annotations.SerializedName

/**
 * 自定义消息
 */
data class CustomMessage(
    @SerializedName("messageType") override val messageType: String = MessageType.CUSTOM.value,
    @SerializedName("sequenceNumber") override val sequenceNumber: Long = 0,
    @SerializedName("serverTimestamp") override val serverTimestamp: Long = 0,
    @SerializedName("senderInfo") override val senderInfo: SenderInfo = SenderInfo(),
    @SerializedName("groupId") override val groupId: String = "",
    @SerializedName("customType") val customType: String = "",
    @SerializedName("payload") val payload: Map<String, Any> = emptyMap()
) : IMMessage()
