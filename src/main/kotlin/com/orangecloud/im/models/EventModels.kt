package com.orangecloud.im.models

/**
 * 状态恢复信息
 */
data class StateRestoredInfo(
    val restoredGroupIds: List<String> = emptyList(),
    val backfilledMessageCount: Int = 0
)

/**
 * 重连尝试信息
 */
data class ReconnectAttemptInfo(
    val attemptNumber: Int = 0,
    val nextDelayMs: Long = 0
)

/**
 * 礼物信息（用于发送）
 */
data class GiftInfo(
    val giftId: String,
    val giftName: String,
    val giftCount: Int = 1,
    val giftPrice: Int = 0,
    val animationUrl: String = ""
)
