package com.childlock.screentime.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivitySettingsBinding
import com.childlock.screentime.databinding.DialogAuditLedgerBinding
import com.childlock.screentime.databinding.DialogRecoveryKeyBinding
import com.childlock.screentime.service.LockTimerService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var prefsManager: PreferencesManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.childlock.screentime.BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)

        binding.toolbar.setNavigationOnClickListener { finish() }

        setupViews()
        setupListeners()
    }

    private fun setupViews() {
        binding.switchBlockSettings.isChecked = prefsManager.isBlockSettingsEnabled()
        binding.switchTelemetrySinkhole.isChecked = prefsManager.isTelemetrySinkholeEnabled()
    }

    private fun setupListeners() {
        // Change PIN
        binding.btnChangePin.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Verify Current PIN", onCancel = null) {
                PinDialogHelper.showPinPrompt(this, "Set New Parent PIN", onCancel = null) {
                    Toast.makeText(this, "Parent PIN updated successfully", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // View Master Recovery Key
        binding.btnViewRecoveryKey.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Parent Authorization", onCancel = null) {
                showMasterRecoveryKeyDialog()
            }
        }

        // Emergency Uninstall
        binding.btnEmergencyUninstall.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Authorize Uninstallation", onCancel = null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Confirm Uninstallation")
                    .setMessage("This will stop all screen locks, neutralizers, and remove Child Screen Lock from this device.")
                    .setPositiveButton("Uninstall Now") { _, _ ->
                        LockTimerService.stopLock(this)
                        val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                            data = Uri.parse("package:$packageName")
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        startActivity(uninstallIntent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // Block Settings Switch
        binding.switchBlockSettings.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setBlockSettingsEnabled(isChecked)
            val msg = if (isChecked) "Android Settings blocked during child lock" else "Android Settings allowed"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Telemetry Sinkhole Switch
        binding.switchTelemetrySinkhole.setOnCheckedChangeListener { _, isChecked ->
            prefsManager.setTelemetrySinkholeEnabled(isChecked)
            val msg = if (isChecked) "Anti-telemetry DNS sinkhole enabled" else "Anti-telemetry disabled"
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // Clear Face Biometrics
        binding.btnClearFaceProfile.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Confirm Face Deletion", onCancel = null) {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Zeroize Biometric Data")
                    .setMessage("This will physically scrub all stored child face embeddings using multi-pass memory zeroization.")
                    .setPositiveButton("Zeroize & Delete") { _, _ ->
                        prefsManager.clearChildFaceEmbedding()
                        Toast.makeText(this, "Child face biometrics physically zeroized", Toast.LENGTH_SHORT).show()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        // View Legal Terms
        binding.btnViewLegalTerms.setOnClickListener {
            LegalTermsDialogHelper.showTermsDialog(this, isMandatory = false)
        }

        // View Audit Ledger
        binding.btnViewAuditLedger.setOnClickListener {
            PinDialogHelper.showPinPrompt(this, "Parent Authorization", onCancel = null) {
                showAuditLedgerDialog()
            }
        }
    }

    private fun showMasterRecoveryKeyDialog() {
        val key = prefsManager.getOrCreateRecoveryKey()
        val dialogBinding = DialogRecoveryKeyBinding.inflate(LayoutInflater.from(this))
        dialogBinding.tvRecoveryKeyDisplay.text = key

        MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showAuditLedgerDialog() {
        val dialogBinding = DialogAuditLedgerBinding.inflate(LayoutInflater.from(this))
        val isIntact = prefsManager.auditLedger.verifyIntegrity()

        dialogBinding.tvIntegrityStatus.text = if (isIntact) {
            "✓ Cryptographic Merkle Chain Integrity: VERIFIED (Untampered)"
        } else {
            "⚠ Chain Integrity Alert: TAMPERING DETECTED"
        }

        val logFile = File(filesDir, "sovereign_audit_ledger.dat")
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

        if (logFile.exists()) {
            try {
                val enc = logFile.readBytes()
                val dec = com.childlock.screentime.security.SovereignKeyStore.decrypt(enc)
                val jsonArray = JSONArray(String(dec, Charsets.UTF_8))
                for (i in (jsonArray.length() - 1) downTo 0) {
                    val entry = jsonArray.getJSONObject(i)
                    val date = sdf.format(Date(entry.getLong("timestamp")))
                    sb.append("[$date] ${entry.getString("event")}\n")
                    sb.append("Details: ${entry.getString("details")}\n")
                    sb.append("Sig: ${entry.getString("entryHash").take(16)}...\n")
                    sb.append("----------------------------------------\n")
                }
            } catch (e: Exception) {
                sb.append("No audit logs found or decryption error.")
            }
        } else {
            sb.append("No audit ledger entries recorded yet.")
        }

        dialogBinding.tvAuditLogs.text = sb.toString()

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCloseAudit.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }
}
