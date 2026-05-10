package com.orangecloud.im.models

import com.google.gson.annotations.SerializedName

/**
 * 系统通知消息
 */
data class SystemNotice(
    @SerializedName("messageType") override val messageType: String = MessageType.SYSTEM_NOTICE.value,
    @SerializedName("sequenceNumber") override val sequenceNumber: Long = 0,
    @SerializedName("serverTimestamp") override val serverTimestamp: Long = 0,
    @SerializedName("senderInfo") override val senderInfo: SenderInfo = SenderInfo(),
    @SerializedName("groupId") override val groupId: String = "",
    @SerializedName("noticeType") val noticeType: String = "",
    @SerializedName("title") val title: String = "",
    @SerializedName("content") val content: String = ""
) : IMMessage()
