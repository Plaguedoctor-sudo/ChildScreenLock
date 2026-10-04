package com.childlock.screentime.ui

import android.content.Context
import android.text.Html
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.DialogLegalTermsBinding
import com.childlock.screentime.security.AuditLedger
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object LegalTermsDialogHelper {

    fun showTermsDialog(
        context: Context,
        isMandatory: Boolean = false,
        onAccepted: (() -> Unit)? = null,
        onDeclined: (() -> Unit)? = null
    ) {
        val prefsManager = PreferencesManager(context)
        val binding = DialogLegalTermsBinding.inflate(LayoutInflater.from(context))
        binding.tvTermsContent.text = Html.fromHtml(getLegalTermsHtml(), Html.FROM_HTML_MODE_LEGACY)

        if (!isMandatory && prefsManager.isTermsAccepted()) {
            binding.cbAcceptTerms.isChecked = true
            binding.btnAcceptTerms.text = "Close"
            binding.btnDeclineTerms.visibility = android.view.View.GONE
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setView(binding.root)
            .setCancelable(!isMandatory)
            .create()

        binding.btnDeclineTerms.setOnClickListener {
            dialog.dismiss()
            onDeclined?.invoke()
        }

        binding.btnAcceptTerms.setOnClickListener {
            binding.cbAcceptTerms.isChecked = true
            prefsManager.setTermsAccepted(true)
            prefsManager.auditLedger.logEvent(
                AuditLedger.EVENT_LOCK_STARTED,
                "Legal Terms of Use and Developer Disclaimer accepted by user"
            )
            dialog.dismiss()
            onAccepted?.invoke()
        }

        dialog.show()
    }

    private fun getLegalTermsHtml(): String {
        return """
            <b>END USER LICENSE AGREEMENT &amp; LEGAL DISCLAIMER</b><br/><br/>
            
            <b>1. Nature &amp; Scope of Software</b><br/>
            Child Screen Lock is an assistive parental control tool designed to aid parents and authorized legal guardians in managing screen time and restricting access to unapproved apps on personal Android devices.<br/><br/>
            
            <b>2. What This Application CAN Do (Capabilities)</b><br/>
            • <b>Local Biometric Comparison:</b> Detects human faces and extracts 128-dimensional embedding vectors using on-device Google ML Kit models to match against the registered child profile.<br/>
            • <b>App Whitelisting &amp; Access Restriction:</b> Utilizes Android Accessibility Services to detect active foreground packages and instantly minimize unapproved applications.<br/>
            • <b>Expansive Timed Lockdown:</b> Enforces continuous sessions from minutes up to 1 Year (365 days) via persistent foreground services.<br/>
            • <b>Anti-Telemetry &amp; Tracker Neutralization:</b> Runs a strictly local on-device loopback VPN sinkhole to intercept and drop DNS queries directed to known ad, telemetry, and tracking domains.<br/>
            • <b>Hardware Cryptographic Storage:</b> Encrypts child face profiles and configuration locally using Android KeyStore AES-256-GCM. No data is ever transmitted to the cloud.<br/><br/>
            
            <b>3. What This Application CANNOT Do (Strict Limitations)</b><br/>
            • <b>NOT a Substitute for Parental Supervision:</b> This software is an assistive tool only and does NOT replace direct in-person parental supervision, care, or physical device control.<br/>
            • <b>NOT an Emergency or Life-Safety System:</b> This app must NEVER be relied upon for life safety, medical monitoring, or emergency dispatch.<br/>
            • <b>Physical Hardware Limits:</b> The app cannot prevent physical hardware manipulation (such as power button long-press shutdown, physical battery drainage, SIM card removal, or recovery-mode factory wipes).<br/>
            • <b>Probabilistic Biometrics:</b> Face recognition is probabilistic. Extreme lighting, severe angles, partial facial occlusions, and natural physical growth may affect recognition reliability.<br/>
            • <b>Network Scope:</b> The telemetry sinkhole neutralizes domain-level tracking locally; it cannot alter cellular baseband firmware telemetry, carrier metadata, or ISP-level logging.<br/>
            • <b>OS Interruption:</b> Custom vendor battery managers (e.g. aggressive OEM task killers) or system-level updates may affect background execution.<br/><br/>
            
            <b>4. Parental Authority &amp; Consent</b><br/>
            By using this application, you expressly certify that you are the legal parent, lawful guardian, or authorized owner of this device. You grant explicit, informed consent for the local processing of facial geometric characteristics strictly on the physical hardware without third-party transmission.<br/><br/>
            
            <b>5. Absolute Waiver of Liability &amp; Developer Indemnification</b><br/>
            <b>THE APPLICATION IS PROVIDED STRICTLY "AS IS" AND "AS AVAILABLE", WITHOUT WARRANTIES OF ANY KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, OR NON-INFRINGEMENT.</b><br/><br/>
            <b>IN NO EVENT SHALL THE DEVELOPER, AUTHORS, CONTRIBUTORS, OR DISTRIBUTORS BE HELD LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, PUNITIVE, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, LOSS OF USE, ACCIDENTAL DEVICE LOCKOUTS, MISSED COMMUNICATIONS OR EMERGENCY CALLS, LOSS OF DATA, BATTERY DRAINAGE, OR ANY DISPUTES ARISING BETWEEN PARENTS, GUARDIANS, AND USERS) ARISING IN ANY WAY OUT OF THE INSTALLATION, USE, OR INABILITY TO USE THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.</b><br/><br/>
            <b>THE USER ASSUMES 100% OF ALL RESPONSIBILITY AND LEGAL LIABILITY FOR THE CONFIGURATION AND USE OF THIS APP.</b>
        """.trimIndent()
    }
}
