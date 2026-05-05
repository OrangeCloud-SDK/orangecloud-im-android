# OrangeCloud IM SDK - Android

[![Platform](https://img.shields.io/badge/platform-Android%2021%2B-3DDC84?logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/OrangeCloud-SDK/orangecloud-im-android/releases)

OrangeCloud IM Android SDK，以 AAR 格式分发。

## 安装

1. 下载 `orangecloud-im-client-release.aar` 放入项目 `libs/` 目录
2. `build.gradle.kts` 中添加：

```kotlin
dependencies {
    implementation(files("libs/orangecloud-im-client-release.aar"))
    implementation("com.microsoft.signalr:signalr:7.+")
}
```

## 快速开始

```kotlin
import com.orangecloud.im.OrangeCloudIMClient
import com.orangecloud.im.OrangeCloudIMClientListener
import com.orangecloud.im.ConnectionState

val client = OrangeCloudIMClient()

client.listener = object : OrangeCloudIMClientListener {
    override fun onMessageReceived(messageJson: String) { /* 处理消息 */ }
    override fun onConnectionStateChanged(state: ConnectionState) { /* 状态变化 */ }
    override fun onUserJoined(userInfoJson: String) {}
    override fun onUserLeft(userKey: String) {}
    override fun onOnlineCountChanged(count: Int) {}
    override fun onMuted(muteInfoJson: String) {}
    override fun onUnmuted(userKey: String) {}
    override fun onRoomClosed() {}
}

// 在后台线程登录
Thread { client.login(hubUrl, appId, userId, userSig) }.start()
```

## Demo

完整 Activity 示例请参考 [orangecloud-im-demos/android](https://github.com/OrangeCloud-SDK/orangecloud-im-demos/tree/main/android)

## License

MIT
