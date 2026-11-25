package com.nosmai.quickstart.zegocloud

import android.content.Context
import java.io.FileNotFoundException
import java.util.Properties

internal class DemoConfig(context: Context) {
    private val values = Properties().apply {
        try {
            context.assets.open("nosmai.properties").bufferedReader().use { load(it) }
        } catch (_: FileNotFoundException) {
            // The role picker explains which settings are required.
        }
    }

    val nosmaiLicenseKey = values.getProperty("nosmaiLicenseKey", "").trim()
    val appID = values.getProperty("appID", "").trim().toLongOrNull() ?: 0L
    val appSign = values.getProperty("appSign", "").trim()
    val roomID = values.getProperty("roomID", "nosmai-live").trim()
    val streamID = roomID + "_host"

    fun problem(isHost: Boolean): String? = when {
        isHost && nosmaiLicenseKey.isBlank() -> "Set nosmaiLicenseKey in nosmai.properties for the host."
        appID <= 0 -> "Set appID in nosmai.properties."
        appSign.isBlank() -> "Set appSign in nosmai.properties."
        roomID.isBlank() -> "Set roomID in nosmai.properties."
        else -> null
    }
}
