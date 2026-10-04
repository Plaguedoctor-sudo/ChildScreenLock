package com.childlock.screentime.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.childlock.screentime.R
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivityMainBinding
import com.childlock.screentime.databinding.DialogCustomDurationBinding
import com.childlock.screentime.service.FaceMonitoringService
import com.childlock.screentime.service.LockTimerService
import com.childlock.screentime.util.PermissionHelper
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefsManager: PreferencesManager

    companion object {
        private var lastParentAuthTime: Long = 0L
        private const val PARENT_AUTH_VALIDITY_MS = 5 * 60 * 1000L // 5 minutes grace period
    }

    private fun isParentSessionActive(): Boolean {
        return (System.currentTimeMillis() - lastParentAuthTime) < PARENT_AUTH_VALIDITY_MS
    }

    private var selectedCustomDurationMinutes: Long = 30L

    private val durationsMinutes = listOf(
        15L,
        30L,
        60L,
        120L,
        240L,
        480L,
        720L,
        1440L,        // 24 hours
        4320L,        // 3 days
        10080L,       // 7 days
        43200L,       // 30 days
        129600L,      // 90 days
        259200L,      // 180 days
        525600L,      // 365 days / 1 Year
        -1L           // Custom
    )

    private val durationLabels = mutableListOf(
        "15 minutes",
        "30 minutes",
        "1 hour",
        "2 hours",
        "4 hours",
        "8 hours",
        "12 hours",
        "24 hours (1 Day)",
        "3 Days",
        "7 Days (1 Week)",
        "30 Days (1 Month)",
        "90 Days (3 Months)",
        "180 Days (6 Months)",
        "365 Days (1 Year)",
        "Custom Duration (Hours, Days, Years)..."
    )

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                LockTimerService.BROADCAST_TICK -> {
                    val remaining = intent.getLongExtra(LockTimerService.EXTRA_REMAINING_MILLIS, 0L)
                    updateTimerUI(remaining)
                }
                LockTimerService.BROADCAST_EXPIRED -> {
                    updateStatusUI()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.childlock.screentime.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)

        setupToolbar()
        setupDurationSpinner()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.main_menu)
        binding.toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_legal_terms -> {
                    LegalTermsDialogHelper.showTermsDialog(this, isMandatory = false)
                    true
                }
                R.id.action_settings -> {
                    if (prefsManager.isLockActive() && !isParentSessionActive()) {
                        PinDialogHelper.showPinPrompt(this, "Parent Authorization Required", onCancel = null) {
                            lastParentAuthTime = System.currentTimeMillis()
                            startActivity(Intent(this, SettingsActivity::class.java))
                        }
                    } else {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onResume() {
        super.onResume()

        // 1. Mandatory Legal Terms Acceptance Check
        if (!prefsManager.isTermsAccepted()) {
            LegalTermsDialogHelper.showTermsDialog(
                context = this,
                isMandatory = true,
                onAccepted = {
                    onResume()
                },
                onDeclined = {
                    finishAffinity()
                }
            )
            return
        }

        // 2. Sovereign First-Time Setup Check
        if (!prefsManager.isPinConfigured()) {
            PinDialogHelper.showPinPrompt(
                context = this,
                title = "First-Time Sovereign Setup",
                onCancel = { finishAffinity() },
                onSuccess = {
                    lastParentAuthTime = System.currentTimeMillis()
                    checkBatteryOptimizations()
                    Toast.makeText(this, "Parent PIN securely established", Toast.LENGTH_SHORT).show()
                    refreshDashboard()
                }
            )
            return
        }

        refreshDashboard()

        val filter = IntentFilter().apply {
            addAction(LockTimerService.BROADCAST_TICK)
            addAction(LockTimerService.BROADCAST_EXPIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(timerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(timerReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(timerReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }

    private fun refreshDashboard() {
        updateStatusUI()
        updateFaceStatus()
        updateAllowedAppsSummary()
        checkPermissionsSummary()
    }

    private fun setupDurationSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, durationLabels)
        binding.spinnerDuration.adapter = adapter
        binding.spinnerDuration.setSelection(1) // Default to 30 mins

        binding.spinnerDuration.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (durationsMinutes[position] == -1L) {
                    showCustomDurationDialog()
                } else {
                    selectedCustomDurationMinutes = durationsMinutes[position]
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showCustomDurationDialog() {
        val dialogBinding = DialogCustomDurationBinding.inflate(LayoutInflater.from(this))
        val units = listOf("Hours", "Days", "Months (30 Days)", "Years (365 Days)")
        val unitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, units)
        dialogBinding.spinnerUnit.adapter = unitAdapter
        dialogBinding.spinnerUnit.setSelection(0)

        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())

        fun updatePreview(): Long {
            val amountStr = dialogBinding.etDurationValue.text?.toString() ?: "1"
            val amount = amountStr.toLongOrNull() ?: 1L
            val unitIndex = dialogBinding.spinnerUnit.selectedItemPosition

            val minutes = when (unitIndex) {
                0 -> amount * 60L                    // Hours
                1 -> amount * 1440L                  // Days
                2 -> amount * 43200L                 // Months
                3 -> amount * 525600L                // Years
                else -> amount * 60L
            }.coerceIn(15L, 525600L) // Maximum 1 year

            val targetMillis = System.currentTimeMillis() + (minutes * 60 * 1000L)
            dialogBinding.tvDurationPreview.text = "Active until: ${dateFormat.format(Date(targetMillis))} (${prefsManager.formatDuration(minutes * 60 * 1000L)})"
            return minutes
        }

        updatePreview()

        dialogBinding.etDurationValue.doAfterTextChanged { updatePreview() }
        dialogBinding.spinnerUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updatePreview()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        val customDialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Set Duration") { _, _ ->
                val calculatedMinutes = updatePreview()
                selectedCustomDurationMinutes = calculatedMinutes
                val label = "Custom: ${prefsManager.formatDuration(calculatedMinutes * 60 * 1000L)}"
                Toast.makeText(this, "Lock duration set to $label", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel") { _, _ ->
                binding.spinnerDuration.setSelection(1) // Revert to 30 mins
            }
            .create()

        customDialog.show()
    }

    private fun setupListeners() {
        binding.btnToggleLock.setOnClickListener {
            if (prefsManager.isLockActive()) {
                // To stop lock, require parent PIN
                PinDialogHelper.showPinPrompt(
                    context = this,
                    title = "Unlock Screen Time",
                    onCancel = null,
                    onSuccess = {
                        lastParentAuthTime = System.currentTimeMillis()
                        LockTimerService.stopLock(this)
                        updateStatusUI()
                        Toast.makeText(this, "Child lock session stopped", Toast.LENGTH_SHORT).show()
                    }
                )
            } else {
                if (!PermissionHelper.hasOverlayPermission(this) || !PermissionHelper.isAccessibilityServiceEnabled(this)) {
                    showPermissionsDialog()
                    return@setOnClickListener
                }

                checkBatteryOptimizations()

                val durationMinutes = selectedCustomDurationMinutes
                LockTimerService.startLock(this, durationMinutes)
                lastParentAuthTime = System.currentTimeMillis()
                updateStatusUI()
                val readableTime = prefsManager.formatDuration(durationMinutes * 60 * 1000L)
                Toast.makeText(this, "Child lock active for $readableTime", Toast.LENGTH_LONG).show()
            }
        }

        binding.switchFaceGuard.setOnCheckedChangeListener { _, isChecked ->
            if (prefsManager.isLockActive() && !isParentSessionActive()) {
                binding.switchFaceGuard.isChecked = !isChecked
                PinDialogHelper.showPinPrompt(this, "Parent Authorization Required", onCancel = null) {
                    lastParentAuthTime = System.currentTimeMillis()
                    binding.switchFaceGuard.isChecked = isChecked
                    prefsManager.setFaceGuardEnabled(isChecked)
                    FaceMonitoringService.stopMonitoring(this)
                    if (isChecked && !prefsManager.hasChildFaceRegistered()) {
                        Toast.makeText(this, "Please register child face first", Toast.LENGTH_SHORT).show()
                    }
                }
                return@setOnCheckedChangeListener
            }
            prefsManager.setFaceGuardEnabled(isChecked)
            FaceMonitoringService.stopMonitoring(this)
            if (isChecked && !prefsManager.hasChildFaceRegistered()) {
                Toast.makeText(this, "Please register child face first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.cardAllowedApps.setOnClickListener {
            if (prefsManager.isLockActive() && !isParentSessionActive()) {
                PinDialogHelper.showPinPrompt(this, "Parent Authorization Required", onCancel = null) {
                    lastParentAuthTime = System.currentTimeMillis()
                    startActivity(Intent(this, AppSelectionActivity::class.java))
                }
            } else {
                startActivity(Intent(this, AppSelectionActivity::class.java))
            }
        }

        binding.cardFaceEnroll.setOnClickListener {
            if (prefsManager.isLockActive() && !isParentSessionActive()) {
                PinDialogHelper.showPinPrompt(this, "Parent Authorization Required", onCancel = null) {
                    lastParentAuthTime = System.currentTimeMillis()
                    startActivity(Intent(this, FaceEnrollmentActivity::class.java))
                }
            } else {
                startActivity(Intent(this, FaceEnrollmentActivity::class.java))
            }
        }

        binding.cardPermissions.setOnClickListener {
            if (prefsManager.isLockActive() && !isParentSessionActive()) {
                PinDialogHelper.showPinPrompt(this, "Parent Authorization Required", onCancel = null) {
                    lastParentAuthTime = System.currentTimeMillis()
                    showPermissionsDialog()
                }
            } else {
                showPermissionsDialog()
            }
        }
    }

    private fun checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    // Ignored
                }
            }
        }
    }

    private fun updateStatusUI() {
        val isLocked = prefsManager.isLockActive()
        if (isLocked) {
            binding.tvLockStatus.text = getString(R.string.lock_status_active)
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.accent_green))
            binding.btnToggleLock.text = getString(R.string.unlock_now)
            binding.btnToggleLock.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_red))
            binding.layoutDurationPicker.visibility = View.GONE
            updateTimerUI(prefsManager.getRemainingMillis())
        } else {
            binding.tvLockStatus.text = getString(R.string.lock_status_inactive)
            binding.tvLockStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
            binding.ivStatusIcon.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))
            binding.btnToggleLock.text = getString(R.string.lock_now)
            binding.btnToggleLock.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
            binding.layoutDurationPicker.visibility = View.VISIBLE
            binding.tvTimeRemaining.text = "Ready to start"
        }

        binding.switchFaceGuard.isChecked = prefsManager.isFaceGuardEnabled()
    }

    private fun updateTimerUI(remainingMillis: Long) {
        binding.tvTimeRemaining.text = "Remaining: " + prefsManager.formatDuration(remainingMillis)
    }

    private fun updateFaceStatus() {
        val count = prefsManager.getChildFacesCount()
        if (count > 0) {
            binding.tvFaceProfileStatus.text = "$count of ${PreferencesManager.MAX_CHILD_FACES} child faces registered.\nDevice is open for adults/others; restricted only for stored child faces."
            binding.tvFaceProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            binding.tvFaceProfileStatus.text = "No child faces registered yet (up to 5).\nTap to enroll children so others can freely use device."
            binding.tvFaceProfileStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
        }
    }

    private fun updateAllowedAppsSummary() {
        val count = prefsManager.getAllowedPackages().size
        binding.tvAllowedAppsCount.text = "$count apps approved for child use"
    }

    private fun checkPermissionsSummary() {
        val overlayOk = PermissionHelper.hasOverlayPermission(this)
        val accessibilityOk = PermissionHelper.isAccessibilityServiceEnabled(this)
        val cameraOk = PermissionHelper.hasCameraPermission(this)

        if (overlayOk && accessibilityOk && cameraOk) {
            binding.tvPermissionsSummary.text = "All required permissions are granted."
            binding.tvPermissionsSummary.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
        } else {
            val missing = mutableListOf<String>()
            if (!accessibilityOk) missing.add("Accessibility")
            if (!overlayOk) missing.add("Overlay")
            if (!cameraOk) missing.add("Camera")
            binding.tvPermissionsSummary.text = "Action needed: Enable ${missing.joinToString(", ")}"
            binding.tvPermissionsSummary.setTextColor(ContextCompat.getColor(this, R.color.accent_red))
        }
    }

    private fun showPermissionsDialog() {
        val overlayOk = PermissionHelper.hasOverlayPermission(this)
        val accessibilityOk = PermissionHelper.isAccessibilityServiceEnabled(this)

        val options = arrayOf(
            "1. Enable Accessibility Service (Block unapproved apps)" + if (accessibilityOk) " [✓ Enabled]" else " [Tap to Enable]",
            "2. Enable Display Over Other Apps (Lock overlay)" + if (overlayOk) " [✓ Enabled]" else " [Tap to Enable]"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("Setup App Locker Permissions")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(PermissionHelper.getAccessibilitySettingsIntent())
                    1 -> startActivity(PermissionHelper.getOverlaySettingsIntent(this))
                }
            }
            .setPositiveButton("Done", null)
            .show()
    }
}
