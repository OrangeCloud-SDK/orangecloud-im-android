package com.orangecloud.im

import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder
import java.util.Timer
import java.util.TimerTask

enum class ConnectionState { CONNECTING, CONNECTED, DISCONNECTED, RECONNECTING }

interface OrangeCloudIMClientListener {
    fun onMessageReceived(messageJson: String)
    fun onUserJoined(userInfoJson: String)
    fun onUserLeft(userKey: String)
    fun onOnlineCountChanged(count: Int)
    fun onMuted(muteInfoJson: String)
    fun onUnmuted(userKey: String)
    fun onRoomClosed()
    fun onConnectionStateChanged(state: ConnectionState)
}

class OrangeCloudIMClient {
    companion object {
        const val VERSION = "1.0.0"
    }
    private var hubConnection: HubConnection? = null
    private var _connectionState: ConnectionState = ConnectionState.DISCONNECTED
    private var hubUrl: String? = null
    private var appId: String? = null
    private var userId: String? = null
    private var userSig: String? = null
    private var reconnectTimer: Timer? = null
    private var reconnectAttempt: Int = 0
    private var manualDisconnect: Boolean = false
    private val reconnectDelays = longArrayOf(0, 2000, 10000, 30000)

    var listener: OrangeCloudIMClientListener? = null
    val connectionState: ConnectionState get() = _connectionState

    fun login(hubUrl: String, appId: String, userId: String, userSig: String) {
        this.hubUrl = hubUrl; this.appId = appId; this.userId = userId; this.userSig = userSig
        manualDisconnect = false; reconnectAttempt = 0
        setConnectionState(ConnectionState.CONNECTING)

        val url = if (hubUrl.contains("?")) "$hubUrl&appId=$appId&userId=$userId&userSig=$userSig"
                  else "$hubUrl?appId=$appId&userId=$userId&userSig=$userSig"

        hubConnection = HubConnectionBuilder.create(url).build()
        registerCallbacks()
        registerConnectionEvents()

        try {
            hubConnection?.start()?.blockingAwait()
            reconnectAttempt = 0
            setConnectionState(ConnectionState.CONNECTED)
        } catch (e: Exception) {
            setConnectionState(ConnectionState.DISCONNECTED)
            scheduleReconnect()
        }
    }

    fun logout() {
        manualDisconnect = true; reconnectTimer?.cancel(); reconnectTimer = null
        hubConnection?.stop()?.blockingAwait(); hubConnection = null
        setConnectionState(ConnectionState.DISCONNECTED)
    }

    fun joinGroup(groupId: String) { hubConnection?.send("JoinGroup", groupId) }
    fun quitGroup(groupId: String) { hubConnection?.send("QuitGroup", groupId) }
    fun sendGroupMsg(groupId: String, messageJson: String) { hubConnection?.send("SendGroupMsg", groupId, messageJson) }
    fun getGroupMemberList(groupId: String) { hubConnection?.send("GetGroupMemberList", groupId) }

    fun dispose() {
        manualDisconnect = true; reconnectTimer?.cancel(); reconnectTimer = null
        hubConnection?.stop(); hubConnection = null; listener = null
    }

    private fun setConnectionState(state: ConnectionState) {
        if (_connectionState != state) { _connectionState = state; listener?.onConnectionStateChanged(state) }
    }

    private fun registerCallbacks() {
        hubConnection?.on("ReceiveMessage", { msg -> listener?.onMessageReceived(msg) }, String::class.java)
        hubConnection?.on("UserJoined", { info -> listener?.onUserJoined(info) }, String::class.java)
        hubConnection?.on("UserLeft", { key -> listener?.onUserLeft(key) }, String::class.java)
        hubConnection?.on("OnlineCountChanged", { count -> listener?.onOnlineCountChanged(count) }, Int::class.java)
        hubConnection?.on("OnMuted", { info -> listener?.onMuted(info) }, String::class.java)
        hubConnection?.on("OnUnmuted", { key -> listener?.onUnmuted(key) }, String::class.java)
        hubConnection?.on("RoomClosed", { listener?.onRoomClosed() })
    }

    private fun registerConnectionEvents() {
        hubConnection?.onClosed { if (!manualDisconnect) { setConnectionState(ConnectionState.DISCONNECTED); scheduleReconnect() } }
    }

    private fun scheduleReconnect() {
        if (manualDisconnect) return
        val delay = if (reconnectAttempt < reconnectDelays.size) reconnectDelays[reconnectAttempt] else reconnectDelays.last()
        reconnectAttempt++; setConnectionState(ConnectionState.RECONNECTING)
        reconnectTimer?.cancel(); reconnectTimer = Timer()
        reconnectTimer?.schedule(object : TimerTask() {
            override fun run() {
                if (manualDisconnect) return
                val u = hubUrl ?: return; val a = appId ?: return; val uid = userId ?: return; val s = userSig ?: return
                login(u, a, uid, s)
            }
        }, delay)
    }
}
