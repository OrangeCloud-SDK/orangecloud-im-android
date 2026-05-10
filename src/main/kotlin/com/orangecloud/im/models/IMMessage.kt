package com.orangecloud.im.models

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

/**
 * 结构化消息类型枚举
 */
enum class MessageType(val value: String) {
    TEXT("text"),
    GIFT("gift"),
    SYSTEM_NOTICE("systemNotice"),
    CUSTOM("custom");

    companion object {
        fun fromValue(value: String): MessageType? =
            entries.firstOrNull { it.value == value }
    }
}

/**
 * IM 消息基类
 */
abstract class IMMessage {
    @SerializedName("messageType")
    open val messageType: String = ""

    @SerializedName("sequenceNumber")
    open val sequenceNumber: Long = 0

    @SerializedName("serverTimestamp")
    open val serverTimestamp: Long = 0

    @SerializedName("senderInfo")
    open val senderInfo: SenderInfo = SenderInfo()

    @SerializedName("groupId")
    open val groupId: String = ""

    /**
     * 序列化为 JSON 字符串
     */
    fun toJson(): String = Gson().toJson(this)

    companion object {
        private val gson = Gson()

        /**
         * 从 JSON 字符串反序列化为具体消息类型
         */
        fun fromJson(json: String): IMMessage? {
            return try {
                val jsonObject = JsonParser.parseString(json).asJsonObject
                val messageType = jsonObject.get("messageType")?.asString ?: return null

                when (MessageType.fromValue(messageType)) {
                    MessageType.TEXT -> gson.fromJson(json, TextMessage::class.java)
                    MessageType.GIFT -> gson.fromJson(json, GiftMessage::class.java)
                    MessageType.SYSTEM_NOTICE -> gson.fromJson(json, SystemNotice::class.java)
                    MessageType.CUSTOM -> gson.fromJson(json, CustomMessage::class.java)
                    null -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
