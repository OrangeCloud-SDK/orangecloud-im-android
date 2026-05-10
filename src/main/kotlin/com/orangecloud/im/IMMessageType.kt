package com.orangecloud.im

/**
 * 业务消息类型常量（向后兼容）
 * 新代码请使用 com.orangecloud.im.models.IMMessageType 枚举
 */
object IMMessageType {
    const val PUBLIC_MSG = "public_msg"
    const val SEND_GIFT = "SEND_GIFT"
    const val SEND_BIG_GIFT = "SEND_BIG_GIFT"
    const val SEND_BARRAGE = "SEND_BARRAGE"
    const val A_NOTICE = "ANotice"
    const val STOP_LIVE = "stop_live"
}
