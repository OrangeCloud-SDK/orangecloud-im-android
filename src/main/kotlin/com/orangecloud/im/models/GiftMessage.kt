package com.orangecloud.im.models

import com.google.gson.annotations.SerializedName

/**
 * 礼物消息
 */
data class GiftMessage(
    @SerializedName("messageType") override val messageType: String = MessageType.GIFT.value,
    @SerializedName("sequenceNumber") override val sequenceNumber: Long = 0,
    @SerializedName("serverTimestamp") override val serverTimestamp: Long = 0,
    @SerializedName("senderInfo") override val senderInfo: SenderInfo = SenderInfo(),
    @SerializedName("groupId") override val groupId: String = "",
    @SerializedName("giftId") val giftId: String = "",
    @SerializedName("giftName") val giftName: String = "",
    @SerializedName("giftCount") val giftCount: Int = 1,
    @SerializedName("giftPrice") val giftPrice: Int = 0,
    @SerializedName("animationUrl") val animationUrl: String = ""
) : IMMessage()
