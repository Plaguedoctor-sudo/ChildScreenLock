package com.childlock.screentime.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.childlock.screentime.ChildLockApp
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.security.AuditLedger

class ChildLockAccessibilityService : AccessibilityService() {

    private lateinit var prefsManager: PreferencesManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastBlockedPackage: String? = null
    private var lastBlockedTime = 0L

    private var lastVerificationPackage: String? = null
    private var lastVerificationTime = 0L

    override fun onCreate() {
        super.onCreate()
        prefsManager = PreferencesManager(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val packageName = event.packageName?.toString() ?: return
        android.util.Log.i("ChildLockA11y", "AccessibilityEvent: pkg=$packageName, class=${event.className}")

        // 1. If child lock session is NOT active, allow all apps
        if (!prefsManager.isLockActive()) return

        // 2. Always allow our own app unconditionally (never block, never prompt biometric)
        if (packageName == this.packageName || packageName == "com.childlock.screentime" || packageName.startsWith("com.childlock.screentime")) {
            return
        }

        // 3. If our own app is currently in foreground, never block system helpers, keyboard, or ext services
        if (ChildLockApp.isAppInForeground) {
            if (isKeyboardOrSystem(packageName)) return
            if (!isUserAppActivity(packageName, event)) return
        }

        // 4. Block Android Settings and App Installers if enabled by parent
        if (prefsManager.isBlockSettingsEnabled() && isSettingsOrInstaller(packageName)) {
            blockApp(packageName, "🚫 Settings is restricted during child lock")
            return
        }

        // 5. Always allow Android OS base, Virtual Keyboards, and Ext/System services
        if (isKeyboardOrSystem(packageName)) return

        // 6. Allow Home Launcher so user can see home screen and pick allowed apps
        if (isLauncherPackage(packageName)) return

        // 7. Google Search widget on home launcher (do not block launcher widget frames)
        if (packageName == "com.google.android.googlequicksearchbox" && event.className?.toString()?.startsWith("android.widget.") == true) {
            return
        }

        // 8. Check if the opened app is in the parent-approved whitelist
        val isAllowed = prefsManager.isPackageAllowed(packageName)
        if (isAllowed) return

        // 9. Check if an adult/other user authorization pass is currently active for this package
        if (prefsManager.isAdultAuthorized(packageName)) return

        // 10. If child faces are registered: launch biometric check so OTHER users can access!
        if (prefsManager.hasChildFaceRegistered() && prefsManager.isFaceGuardEnabled()) {
            verifyUserBiometrically(packageName)
            return
        }

        // 11. Ignore non-activity views / layout frames from non-launchable background packages
        if (!isUserAppActivity(packageName, event) && packageManager.getLaunchIntentForPackage(packageName) == null) {
            return
        }

        // 12. Otherwise, block unapproved app
        blockApp(packageName, "🚫 This app is locked by parent")
    }

    private fun verifyUserBiometrically(packageName: String) {
        val now = System.currentTimeMillis()
        if (packageName == lastVerificationPackage && (now - lastVerificationTime) < 3000L) {
            return
        }
        lastVerificationPackage = packageName
        lastVerificationTime = now

        val intent = Intent(this, com.childlock.screentime.ui.FaceVerificationActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(com.childlock.screentime.ui.FaceVerificationActivity.EXTRA_TARGET_PACKAGE, packageName)
        }
        startActivity(intent)
    }

    private fun blockApp(packageName: String, toastMessage: String) {
        android.util.Log.e("ChildLockA11y", "BLOCKING APP: pkg=$packageName, msg=$toastMessage")
        // Instantly kick back to home screen
        performGlobalAction(GLOBAL_ACTION_HOME)
        
        prefsManager.auditLedger.logEvent(
            AuditLedger.EVENT_APP_BLOCKED,
            "Package: $packageName"
        )

        // Show brief Toast notification (debounced to avoid spamming)
        val now = System.currentTimeMillis()
        if (packageName != lastBlockedPackage || (now - lastBlockedTime) > 2000L) {
            lastBlockedPackage = packageName
            lastBlockedTime = now
            mainHandler.post {
                Toast.makeText(applicationContext, toastMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isSettingsOrInstaller(packageName: String): Boolean {
        if (packageName.startsWith("com.childlock.screentime")) return false
        return packageName == "com.android.settings" ||
                packageName == "com.samsung.android.settings" ||
                packageName == "com.oplus.wirelesssettings" ||
                packageName.contains(".settings") ||
                packageName.contains("packageinstaller")
    }

    private fun isKeyboardOrSystem(packageName: String): Boolean {
        if (packageName == "android" || packageName == "com.android.systemui") return true
        if (packageName.contains("systemui")) return true

        // Android Ext Services, Play Services, Credential Manager, Autofill
        if (packageName.contains("ext.services") ||
            packageName.startsWith("com.google.android.ext.services") ||
            packageName.startsWith("com.android.ext.services")) {
            return true
        }
        if (packageName.startsWith("com.google.android.gms") ||
            packageName.startsWith("com.google.android.gsf")) {
            return true
        }
        if (packageName.contains("credentialmanager") ||
            packageName.contains("autofill") ||
            packageName.contains("permissioncontroller")) {
            return true
        }

        // Virtual Keyboards and Input Method Engines across all OEMs (Samsung Honeyboard, Gboard, SwiftKey, etc.)
        if (packageName.contains("inputmethod") ||
            packageName.contains("keyboard") ||
            packageName.contains("honeyboard") ||
            packageName.contains("gboard") ||
            packageName.contains("swiftkey") ||
            packageName.contains("ime")) {
            return true
        }

        // Samsung One UI system & input packages
        if (packageName.startsWith("com.samsung.android.honeyboard") ||
            packageName.startsWith("com.sec.android.inputmethod") ||
            packageName.startsWith("com.samsung.android.incallui") ||
            packageName.startsWith("com.samsung.android.dialer") ||
            packageName.startsWith("com.samsung.android.biometrics") ||
            packageName.startsWith("com.samsung.internal.systemui") ||
            packageName.startsWith("com.sec.android.app.launcher") ||
            packageName.startsWith("com.samsung.android.app.telephonyui") ||
            packageName.startsWith("com.samsung.android.accessibility") ||
            packageName.startsWith("com.sec.android.sdhms")) {
            return true
        }

        // Comprehensive OEM System UI & Services (OPPO, ColorOS, Samsung, Xiaomi, Pixel)
        if (packageName.startsWith("com.oplus.") ||
            packageName.startsWith("com.coloros.") ||
            packageName.startsWith("com.android.server.telecom") ||
            packageName.startsWith("com.google.android.dialer") ||
            packageName.startsWith("com.android.dialer") ||
            packageName.startsWith("com.android.incallui") ||
            packageName.startsWith("com.miui.securitycenter") ||
            packageName.startsWith("com.miui.home")) {
            return true
        }

        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        val enabledImePackages = imm?.enabledInputMethodList?.map { it.packageName }
        if (enabledImePackages?.contains(packageName) == true) return true

        return false
    }

    private fun isUserAppActivity(packageName: String, event: AccessibilityEvent): Boolean {
        val className = event.className?.toString() ?: return false
        if (className.startsWith("android.widget.") ||
            className.startsWith("android.view.") ||
            className.contains("SoftInputWindow") ||
            className.contains("Dialog") ||
            className.contains("PopupWindow")) {
            return false
        }
        if (packageManager.getLaunchIntentForPackage(packageName) == null) return false
        return try {
            val component = android.content.ComponentName(packageName, className)
            packageManager.getActivityInfo(component, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun isLauncherPackage(packageName: String): Boolean {
        if (packageName.contains("launcher") || packageName.contains("home") || packageName.contains("trebuchet") || packageName.startsWith("com.sec.android.app.launcher")) return true
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolveInfos = packageManager.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL)
        return resolveInfos.any { it.activityInfo?.packageName == packageName }
    }

    override fun onInterrupt() {
    }
}
