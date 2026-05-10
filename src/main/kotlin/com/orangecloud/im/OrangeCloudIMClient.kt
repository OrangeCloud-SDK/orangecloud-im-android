package com.orangecloud.im

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import com.orangecloud.im.models.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接状态枚举（扩展版）
 */
enum class IMConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
    RESTORING  // 新增：状态恢复中
}

/**
 * 向后兼容的旧连接状态枚举别名
 */
@Deprecated("Use IMConnectionState instead", ReplaceWith("IMConnectionState"))
typealias ConnectionState = IMConnectionState

/**
 * 向后兼容的监听器接口
 */
interface OrangeCloudIMClientListener {
    fun onMessageReceived(messageJson: String)
    fun onUserJoined(userInfoJson: String)
    fun onUserLeft(userKey: String)
    fun onOnlineCountChanged(count: Int)
    fun onMuted(muteInfoJson: String)
    fun onUnmuted(userKey: String)
    fun onRoomClosed()
    fun onConnectionStateChanged(state: IMConnectionState)
}

/**
 * OrangeCloud IM 客户端 SDK（增强版）
 *
 * 新增功能：
 * - 结构化消息类型（TextMessage、GiftMessage、SystemNotice、CustomMessage）
 * - 类型安全的事件回调（SharedFlow）
 * - 消息序列号去重与间隙检测
 * - 全局广播、批量消息接收
 * - 断线重连状态恢复
 * - 心跳机制
 * - 网络状态监听
 */
class OrangeCloudIMClient {
    companion object {
        const val VERSION = "2.0.0"
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val gson = Gson()

    private var hubConnection: HubConnection? = null
    private var _connectionState: IMConnectionState = IMConnectionState.DISCONNECTED
    private var hubUrl: String? = null
    private var appId: String? = null
    private var userId: String? = null
    private var userSig: String? = null
    private var reconnectTimer: Timer? = null
    private var reconnectAttempt: Int = 0
    private var manualDisconnect: Boolean = false
    private var heartbeatTimer: Timer? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    private val reconnectDelays = longArrayOf(0, 2000, 5000, 10000, 30000)

    // === 配置 ===
    /** 最大重连次数，-1 表示无限重连 */
    var maxReconnectAttempts: Int = -1

    // === 向后兼容的监听器 ===
    var listener: OrangeCloudIMClientListener? = null

    // === 连接状态 ===
    val connectionState: IMConnectionState get() = _connectionState

    // === 序列号跟踪（10.3） ===
    private val lastSequenceNumbers = ConcurrentHashMap<String, Long>()

    // === 类型安全的事件流（10.4） ===
    private val _onTextMessage = MutableSharedFlow<TextMessage>(extraBufferCapacity = 64)
    val onTextMessage: SharedFlow<TextMessage> = _onTextMessage.asSharedFlow()

    private val _onGiftMessage = MutableSharedFlow<GiftMessage>(extraBufferCapacity = 64)
    val onGiftMessage: SharedFlow<GiftMessage> = _onGiftMessage.asSharedFlow()

    private val _onSystemNotice = MutableSharedFlow<SystemNotice>(extraBufferCapacity = 64)
    val onSystemNotice: SharedFlow<SystemNotice> = _onSystemNotice.asSharedFlow()

    private val _onCustomMessage = MutableSharedFlow<CustomMessage>(extraBufferCapacity = 64)
    val onCustomMessage: SharedFlow<CustomMessage> = _onCustomMessage.asSharedFlow()

    // === 广播、批量、状态恢复事件（10.5） ===
    private val _onBroadcastReceived = MutableSharedFlow<IMMessage>(extraBufferCapacity = 64)
    val onBroadcastReceived: SharedFlow<IMMessage> = _onBroadcastReceived.asSharedFlow()

    private val _onBatchMessageReceived = MutableSharedFlow<List<IMMessage>>(extraBufferCapacity = 64)
    val onBatchMessageReceived: SharedFlow<List<IMMessage>> = _onBatchMessageReceived.asSharedFlow()

    private val _onStateRestored = MutableSharedFlow<StateRestoredInfo>(extraBufferCapacity = 8)
    val onStateRestored: SharedFlow<StateRestoredInfo> = _onStateRestored.asSharedFlow()

    // === 重连事件（10.6） ===
    private val _onReconnectAttempt = MutableSharedFlow<ReconnectAttemptInfo>(extraBufferCapacity = 16)
    val onReconnectAttempt: SharedFlow<ReconnectAttemptInfo> = _onReconnectAttempt.asSharedFlow()

    private val _onReconnectFailed = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val onReconnectFailed: SharedFlow<Unit> = _onReconnectFailed.asSharedFlow()

    // === 连接状态变化事件 ===
    private val _onConnectionStateChanged = MutableSharedFlow<IMConnectionState>(extraBufferCapacity = 8)
    val onConnectionStateChanged: SharedFlow<IMConnectionState> = _onConnectionStateChanged.asSharedFlow()

    /**
     * 登录并连接到 IM 服务
     */
    fun login(hubUrl: String, appId: String, userId: String, userSig: String) {
        this.hubUrl = hubUrl
        this.appId = appId
        this.userId = userId
        this.userSig = userSig
        manualDisconnect = false
        reconnectAttempt = 0
        setConnectionState(IMConnectionState.CONNECTING)

        val url = if (hubUrl.contains("?"))
            "$hubUrl&appId=$appId&userId=$userId&userSig=$userSig"
        else
            "$hubUrl?appId=$appId&userId=$userId&userSig=$userSig"

        hubConnection = HubConnectionBuilder.create(url).build()
        registerCallbacks()
        registerConnectionEvents()

        try {
            hubConnection?.start()?.blockingAwait()
            reconnectAttempt = 0
            setConnectionState(IMConnectionState.CONNECTED)
            startHeartbeat()
        } catch (e: Exception) {
            setConnectionState(IMConnectionState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    /**
     * 登出并断开连接
     */
    fun logout() {
        manualDisconnect = true
        reconnectTimer?.cancel()
        reconnectTimer = null
        stopHeartbeat()
        unregisterNetworkCallback()
        lastSequenceNumbers.clear()
        hubConnection?.stop()?.blockingAwait()
        hubConnection = null
        setConnectionState(IMConnectionState.DISCONNECTED)
    }

    /**
     * 加入群组
     */
    fun joinGroup(groupId: String) {
        val lastSeq = lastSequenceNumbers[groupId] ?: 0
        hubConnection?.send("JoinGroup", groupId, lastSeq)
    }

    /**
     * 退出群组
     */
    fun quitGroup(groupId: String) {
        hubConnection?.send("QuitGroup", groupId)
        lastSequenceNumbers.remove(groupId)
    }

    /**
     * 发送原始消息（向后兼容）
     */
    fun sendGroupMsg(groupId: String, messageJson: String) {
        hubConnection?.send("SendGroupMsg", groupId, messageJson)
    }

    /**
     * 获取群组成员列表
     */
    fun getGroupMemberList(groupId: String) {
        hubConnection?.send("GetGroupMemberList", groupId)
    }

    // === 类型安全的发送方法（10.8） ===

    /**
     * 发送文本消息
     */
    fun sendTextMessage(groupId: String, content: String) {
        val message = mapOf(
            "messageType" to MessageType.TEXT.value,
            "content" to content,
            "groupId" to groupId
        )
        hubConnection?.send("SendGroupMsg", groupId, gson.toJson(message))
    }

    /**
     * 发送礼物消息
     */
    fun sendGiftMessage(groupId: String, giftInfo: GiftInfo) {
        val message = mapOf(
            "messageType" to MessageType.GIFT.value,
            "giftId" to giftInfo.giftId,
            "giftName" to giftInfo.giftName,
            "giftCount" to giftInfo.giftCount,
            "giftPrice" to giftInfo.giftPrice,
            "animationUrl" to giftInfo.animationUrl,
            "groupId" to groupId
        )
        hubConnection?.send("SendGroupMsg", groupId, gson.toJson(message))
    }

    /**
     * 发送自定义消息
     */
    fun sendCustomMessage(groupId: String, customType: String, payload: Map<String, Any>) {
        val message = mapOf(
            "messageType" to MessageType.CUSTOM.value,
            "customType" to customType,
            "payload" to payload,
            "groupId" to groupId
        )
        hubConnection?.send("SendGroupMsg", groupId, gson.toJson(message))
    }

    // === 序列号管理（10.8） ===

    /**
     * 获取指定群组的最后序列号
     */
    fun getLastSequenceNumber(groupId: String): Long {
        return lastSequenceNumbers[groupId] ?: 0
    }

    // === 网络状态监听（10.7） ===

    /**
     * 注册网络状态监听，网络恢复时立即触发重连
     */
    fun registerNetworkCallback(context: Context) {
        connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // 网络恢复时，如果当前处于断连或重连状态，立即触发重连
                if (!manualDisconnect &&
                    (_connectionState == IMConnectionState.DISCONNECTED ||
                     _connectionState == IMConnectionState.RECONNECTING)) {
                    reconnectTimer?.cancel()
                    reconnectTimer = null
                    reconnectAttempt = 0
                    scheduleReconnect()
                }
            }
        }

        connectivityManager?.registerNetworkCallback(request, networkCallback!!)
    }

    /**
     * 释放资源
     */
    fun dispose() {
        manualDisconnect = true
        reconnectTimer?.cancel()
        reconnectTimer = null
        stopHeartbeat()
        unregisterNetworkCallback()
        lastSequenceNumbers.clear()
        hubConnection?.stop()
        hubConnection = null
        listener = null
    }

    // === 私有方法 ===

    private fun setConnectionState(state: IMConnectionState) {
        if (_connectionState != state) {
            _connectionState = state
            listener?.onConnectionStateChanged(state)
            scope.launch { _onConnectionStateChanged.emit(state) }
        }
    }

    private fun registerCallbacks() {
        // 向后兼容：原始消息回调
        hubConnection?.on("ReceiveMessage", { msg ->
            listener?.onMessageReceived(msg)
            handleStructuredMessage(msg)
        }, String::class.java)

        // 原有回调
        hubConnection?.on("UserJoined", { info -> listener?.onUserJoined(info) }, String::class.java)
        hubConnection?.on("UserLeft", { key -> listener?.onUserLeft(key) }, String::class.java)
        hubConnection?.on("OnlineCountChanged", { count -> listener?.onOnlineCountChanged(count) }, Int::class.java)
        hubConnection?.on("OnMuted", { info -> listener?.onMuted(info) }, String::class.java)
        hubConnection?.on("OnUnmuted", { key -> listener?.onUnmuted(key) }, String::class.java)
        hubConnection?.on("RoomClosed", { listener?.onRoomClosed() })

        // 全局广播回调（10.5）
        hubConnection?.on("ReceiveBroadcast", { msg ->
            handleBroadcastMessage(msg)
        }, String::class.java)

        // 批量消息回调（10.5）
        hubConnection?.on("ReceiveBatchMessage", { msg ->
            handleBatchMessage(msg)
        }, String::class.java)

        // 状态恢复回调（10.5）
        hubConnection?.on("StateRestored", { msg ->
            handleStateRestored(msg)
        }, String::class.java)
    }

    private fun registerConnectionEvents() {
        hubConnection?.onClosed {
            stopHeartbeat()
            if (!manualDisconnect) {
                setConnectionState(IMConnectionState.DISCONNECTED)
                scheduleReconnect()
            }
        }
    }

    /**
     * 处理结构化消息：去重、间隙检测、类型路由
     */
    private fun handleStructuredMessage(messageJson: String) {
        try {
            val jsonObject = JsonParser.parseString(messageJson).asJsonObject

            // 提取序列号和群组ID
            val sequenceNumber = jsonObject.get("sequenceNumber")?.asLong ?: return
            val groupId = jsonObject.get("groupId")?.asString ?: return

            // 去重逻辑（10.3）：如果 seq <= lastSeq，丢弃
            val lastSeq = lastSequenceNumbers[groupId] ?: 0
            if (sequenceNumber <= lastSeq) {
                return // 重复消息，丢弃
            }

            // 间隙检测（10.3）：如果 seq - lastSeq > 1，请求补发
            if (lastSeq > 0 && sequenceNumber - lastSeq > 1) {
                requestBackfill(groupId, lastSeq, sequenceNumber)
            }

            // 更新 lastSequenceNumber
            lastSequenceNumbers[groupId] = sequenceNumber

            // 类型路由（10.4）
            val messageType = jsonObject.get("messageType")?.asString
            val message = IMMessage.fromJson(messageJson) ?: return

            scope.launch {
                when (MessageType.fromValue(messageType ?: "")) {
                    MessageType.TEXT -> _onTextMessage.emit(message as TextMessage)
                    MessageType.GIFT -> _onGiftMessage.emit(message as GiftMessage)
                    MessageType.SYSTEM_NOTICE -> _onSystemNotice.emit(message as SystemNotice)
                    MessageType.CUSTOM -> _onCustomMessage.emit(message as CustomMessage)
                    null -> { /* 未知类型，忽略 */ }
                }
            }
        } catch (_: Exception) {
            // 解析失败，静默处理
        }
    }

    /**
     * 处理全局广播消息
     */
    private fun handleBroadcastMessage(messageJson: String) {
        try {
            val message = IMMessage.fromJson(messageJson) ?: return
            scope.launch { _onBroadcastReceived.emit(message) }
        } catch (_: Exception) {
            // 解析失败，静默处理
        }
    }

    /**
     * 处理批量消息
     */
    private fun handleBatchMessage(batchJson: String) {
        try {
            val jsonObject = JsonParser.parseString(batchJson).asJsonObject
            val messagesArray = jsonObject.getAsJsonArray("messages") ?: return
            val messages = mutableListOf<IMMessage>()

            for (element in messagesArray) {
                val msgJson = element.toString()
                // 对每条消息执行去重和序列号更新
                val msgObj = element.asJsonObject
                val sequenceNumber = msgObj.get("sequenceNumber")?.asLong ?: continue
                val groupId = msgObj.get("groupId")?.asString ?: continue

                val lastSeq = lastSequenceNumbers[groupId] ?: 0
                if (sequenceNumber <= lastSeq) continue // 去重

                lastSequenceNumbers[groupId] = sequenceNumber

                val message = IMMessage.fromJson(msgJson)
                if (message != null) {
                    messages.add(message)
                    // 同时分发到类型回调
                    scope.launch {
                        when (message) {
                            is TextMessage -> _onTextMessage.emit(message)
                            is GiftMessage -> _onGiftMessage.emit(message)
                            is SystemNotice -> _onSystemNotice.emit(message)
                            is CustomMessage -> _onCustomMessage.emit(message)
                        }
                    }
                }

                // 向后兼容：触发 onMessageReceived
                listener?.onMessageReceived(msgJson)
            }

            if (messages.isNotEmpty()) {
                scope.launch { _onBatchMessageReceived.emit(messages) }
            }
        } catch (_: Exception) {
            // 解析失败，静默处理
        }
    }

    /**
     * 处理状态恢复事件
     */
    private fun handleStateRestored(json: String) {
        try {
            val jsonObject = JsonParser.parseString(json).asJsonObject
            val groupIds = jsonObject.getAsJsonArray("groupIds")
                ?.map { it.asString } ?: emptyList()
            val backfilledCount = jsonObject.get("backfilledCount")?.asInt ?: 0

            val info = StateRestoredInfo(
                restoredGroupIds = groupIds,
                backfilledMessageCount = backfilledCount
            )
            setConnectionState(IMConnectionState.CONNECTED)
            scope.launch { _onStateRestored.emit(info) }
        } catch (_: Exception) {
            // 解析失败，静默处理
        }
    }

    /**
     * 请求服务端补发缺失的消息
     */
    private fun requestBackfill(groupId: String, afterSeq: Long, beforeSeq: Long) {
        try {
            hubConnection?.send("RequestBackfill", groupId, afterSeq)
        } catch (_: Exception) {
            // 补发请求失败，静默处理
        }
    }

    /**
     * 重连调度（10.6、10.7）
     */
    private fun scheduleReconnect() {
        if (manualDisconnect) return

        // 检查是否超过最大重连次数（10.6）
        if (maxReconnectAttempts > 0 && reconnectAttempt >= maxReconnectAttempts) {
            setConnectionState(IMConnectionState.DISCONNECTED)
            scope.launch { _onReconnectFailed.emit(Unit) }
            return
        }

        val delay = if (reconnectAttempt < reconnectDelays.size)
            reconnectDelays[reconnectAttempt]
        else
            reconnectDelays.last()

        reconnectAttempt++
        setConnectionState(IMConnectionState.RECONNECTING)

        // 发送重连尝试事件
        scope.launch {
            _onReconnectAttempt.emit(
                ReconnectAttemptInfo(
                    attemptNumber = reconnectAttempt,
                    nextDelayMs = delay
                )
            )
        }

        reconnectTimer?.cancel()
        reconnectTimer = Timer()
        reconnectTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (manualDisconnect) return
                performReconnect()
            }
        }, delay)
    }

    /**
     * 执行重连（10.7）：携带 lastSequenceNumber
     */
    private fun performReconnect() {
        val u = hubUrl ?: return
        val a = appId ?: return
        val uid = userId ?: return
        val s = userSig ?: return

        setConnectionState(IMConnectionState.CONNECTING)

        val url = if (u.contains("?"))
            "$u&appId=$a&userId=$uid&userSig=$s"
        else
            "$u?appId=$a&userId=$uid&userSig=$s"

        hubConnection?.stop()
        hubConnection = HubConnectionBuilder.create(url).build()
        registerCallbacks()
        registerConnectionEvents()

        try {
            hubConnection?.start()?.blockingAwait()
            reconnectAttempt = 0

            // 重连成功后进入恢复状态
            if (lastSequenceNumbers.isNotEmpty()) {
                setConnectionState(IMConnectionState.RESTORING)
                // 携带 lastSequenceNumber 重新加入群组
                for ((groupId, lastSeq) in lastSequenceNumbers) {
                    hubConnection?.send("JoinGroup", groupId, lastSeq)
                }
            } else {
                setConnectionState(IMConnectionState.CONNECTED)
            }

            startHeartbeat()
        } catch (e: Exception) {
            setConnectionState(IMConnectionState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    // === 心跳机制（10.9） ===

    /**
     * 启动心跳定时器
     */
    private fun startHeartbeat() {
        stopHeartbeat()
        heartbeatTimer = Timer()
        heartbeatTimer?.scheduleAtFixedRate(object : TimerTask() {
            override fun run() {
                try {
                    hubConnection?.send("Heartbeat")
                } catch (_: Exception) {
                    // 心跳发送失败，可能连接已断开
                    stopHeartbeat()
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS)
    }

    /**
     * 停止心跳定时器
     */
    private fun stopHeartbeat() {
        heartbeatTimer?.cancel()
        heartbeatTimer = null
    }

    /**
     * 注销网络回调
     */
    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            try {
                connectivityManager?.unregisterNetworkCallback(it)
            } catch (_: Exception) {
                // 可能已经注销
            }
        }
        networkCallback = null
        connectivityManager = null
    }
}
