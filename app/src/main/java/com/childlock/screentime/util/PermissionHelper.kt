package com.childlock.screentime.util

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import com.childlock.screentime.service.ChildLockAccessibilityService

object PermissionHelper {

    fun hasCameraPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasOverlayPermission(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        try {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            if (!enabledServices.isNullOrEmpty()) {
                val colonSplitter = TextUtils.SimpleStringSplitter(':')
                colonSplitter.setString(enabledServices)
                while (colonSplitter.hasNext()) {
                    val componentName = colonSplitter.next()
                    if (componentName.contains(context.packageName, ignoreCase = true) &&
                        componentName.contains("ChildLockAccessibilityService", ignoreCase = true)) {
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            // fall through to AccessibilityManager check
        }

        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK) ?: emptyList()
        val expectedPackage = context.packageName
        return enabledServices.any { 
            it.resolveInfo?.serviceInfo?.packageName == expectedPackage &&
            it.resolveInfo?.serviceInfo?.name?.contains(ChildLockAccessibilityService::class.java.simpleName, ignoreCase = true) == true
        }
    }

    fun allCorePermissionsGranted(context: Context): Boolean {
        return hasOverlayPermission(context) &&
               isAccessibilityServiceEnabled(context)
    }

    fun getOverlaySettingsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
    }

    fun getAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    fun getAppDetailsSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }
}
