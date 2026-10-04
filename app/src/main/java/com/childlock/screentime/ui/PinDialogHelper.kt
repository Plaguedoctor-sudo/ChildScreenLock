package com.childlock.screentime.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.DialogEnterRecoveryKeyBinding
import com.childlock.screentime.databinding.DialogPinEntryBinding
import com.childlock.screentime.security.CredentialPolicyValidator
import com.childlock.screentime.service.LockTimerService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object PinDialogHelper {

    fun showPinPrompt(
        context: Context,
        title: String = "Enter Parent PIN",
        onCancel: (() -> Unit)? = null,
        onSuccess: () -> Unit
    ) {
        val prefsManager = PreferencesManager(context)
        val isFirstTimeSetup = !prefsManager.isPinConfigured()

        val binding = DialogPinEntryBinding.inflate(LayoutInflater.from(context))
        
        if (isFirstTimeSetup) {
            binding.tvPinDialogTitle.text = "Setup Parent PIN"
            binding.tvPinDialogSubtitle.text = "Create a secure 4 to 8 digit PIN. Avoid sequential or default patterns (e.g. 1234)."
            binding.btnForgotPin.visibility = View.GONE
        } else {
            binding.tvPinDialogTitle.text = title
            binding.tvPinDialogSubtitle.text = "Enter your parent PIN to proceed."
            binding.btnForgotPin.visibility = View.VISIBLE
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton(if (isFirstTimeSetup) "Set PIN" else "Verify", null)
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
                onCancel?.invoke()
            }
            .setOnCancelListener {
                onCancel?.invoke()
            }
            .create()

        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
        )

        binding.btnForgotPin.setOnClickListener {
            dialog.dismiss()
            showEmergencyRecoveryDialog(context, onCancel, onSuccess)
        }

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)

            binding.etPin.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(binding.etPin, InputMethodManager.SHOW_IMPLICIT)

            binding.etPin.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    positiveButton.performClick()
                    true
                } else {
                    false
                }
            }

            if (!isFirstTimeSetup) {
                val status = prefsManager.rateLimiter.checkStatus()
                if (status.isLockedOut) {
                    binding.tvPinError.text = "Locked out due to repeated attempts. Cooldown: ${status.remainingCooldownSeconds}s"
                    binding.tvPinError.visibility = View.VISIBLE
                    positiveButton.isEnabled = false
                }
            }

            positiveButton.setOnClickListener {
                val inputPin = binding.etPin.text?.toString() ?: ""

                if (isFirstTimeSetup) {
                    // Blueprint §3 & §12.B: Validate sovereign credential policy
                    val validation = CredentialPolicyValidator.validatePin(inputPin)
                    if (!validation.isValid) {
                        binding.tvPinError.text = validation.reason ?: "Invalid PIN format."
                        binding.tvPinError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    prefsManager.setParentPin(inputPin)
                    binding.tvPinError.visibility = View.GONE
                    dialog.dismiss()
                    onSuccess()
                } else {
                    // Blueprint §4 & §5: Memory-hard constant time verification with rate limiting
                    val rateStatus = prefsManager.rateLimiter.checkStatus()
                    if (rateStatus.isLockedOut) {
                        binding.tvPinError.text = "Rate limit active. Try again in ${rateStatus.remainingCooldownSeconds}s."
                        binding.tvPinError.visibility = View.VISIBLE
                        positiveButton.isEnabled = false
                        return@setOnClickListener
                    }

                    if (prefsManager.verifyPin(inputPin)) {
                        binding.tvPinError.visibility = View.GONE
                        dialog.dismiss()
                        onSuccess()
                    } else {
                        val newStatus = prefsManager.rateLimiter.checkStatus()
                        if (newStatus.isLockedOut) {
                            binding.tvPinError.text = "Maximum attempts exceeded. Locked out for ${newStatus.remainingCooldownSeconds}s."
                            positiveButton.isEnabled = false
                        } else {
                            binding.tvPinError.text = "Incorrect PIN. Attempts remaining: ${newStatus.remainingAttempts}"
                        }
                        binding.tvPinError.visibility = View.VISIBLE
                        binding.etPin.text?.clear()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showEmergencyRecoveryDialog(
        context: Context,
        onCancel: (() -> Unit)?,
        onSuccess: () -> Unit
    ) {
        val prefsManager = PreferencesManager(context)
        val binding = DialogEnterRecoveryKeyBinding.inflate(LayoutInflater.from(context))

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(false)
            .setPositiveButton("Verify Key", null)
            .setNegativeButton("Cancel") { d, _ ->
                d.dismiss()
                onCancel?.invoke()
            }
            .create()

        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val candidateKey = binding.etRecoveryKey.text?.toString() ?: ""
                if (prefsManager.verifyRecoveryKey(candidateKey)) {
                    binding.tvRecoveryError.visibility = View.GONE
                    dialog.dismiss()

                    // Recovery choices: Reset PIN or Uninstall App
                    val options = arrayOf(
                        "1. Reset Parent PIN",
                        "2. Emergency Uninstall Application",
                        "3. Unlock Session & Open Dashboard"
                    )

                    MaterialAlertDialogBuilder(context)
                        .setTitle("Master Recovery Authenticated")
                        .setItems(options) { _, which ->
                            when (which) {
                                0 -> {
                                    showPinPrompt(context, "Set New Parent PIN", onCancel = null) {
                                        Toast.makeText(context, "New PIN established", Toast.LENGTH_SHORT).show()
                                        onSuccess()
                                    }
                                }
                                1 -> {
                                    // Stop locks and launch uninstallation
                                    LockTimerService.stopLock(context)
                                    val uninstallIntent = Intent(Intent.ACTION_DELETE).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(uninstallIntent)
                                }
                                2 -> {
                                    LockTimerService.stopLock(context)
                                    onSuccess()
                                }
                            }
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    binding.tvRecoveryError.text = "Invalid Master Recovery Key. Please check the key."
                    binding.tvRecoveryError.visibility = View.VISIBLE
                }
            }
        }

        dialog.show()
    }
}
