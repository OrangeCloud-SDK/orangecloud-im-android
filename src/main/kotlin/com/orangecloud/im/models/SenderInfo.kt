package com.orangecloud.im.models

import com.google.gson.annotations.SerializedName

data class SenderInfo(
    @SerializedName("userId") val userId: String = "",
    @SerializedName("nickName") val nickName: String = "",
    @SerializedName("faceUrl") val faceUrl: String = "",
    @SerializedName("level") val level: String = "",
    @SerializedName("isAdmin") val isAdmin: Boolean = false,
    @SerializedName("isAnchor") val isAnchor: Boolean = false
)
