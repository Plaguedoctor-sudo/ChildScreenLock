package com.childlock.screentime.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.childlock.screentime.data.AppInfo
import com.childlock.screentime.data.PreferencesManager
import com.childlock.screentime.databinding.ActivityAppSelectionBinding
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppSelectionBinding
    private lateinit var prefsManager: PreferencesManager
    private lateinit var adapter: AppAdapter
    private val appList = mutableListOf<AppInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.childlock.screentime.BuildConfig.DEBUG) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        binding = ActivityAppSelectionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefsManager = PreferencesManager(this)

        binding.toolbar.setNavigationOnClickListener {
            saveAllowedAppsSilently()
            finish()
        }

        setupRecyclerView()
        loadInstalledApps()

        binding.etSearch.doAfterTextChanged { text ->
            adapter.filter(text?.toString() ?: "")
        }

        binding.btnSave.setOnClickListener {
            saveAllowedApps()
        }
    }

    override fun onPause() {
        super.onPause()
        saveAllowedAppsSilently()
    }

    private fun setupRecyclerView() {
        adapter = AppAdapter(appList) { appInfo, isChecked ->
            if (appInfo.packageName == packageName) {
                // Own app is permanently allowed and exempt
                appInfo.isAllowed = true
                adapter.notifyItemChanged(0)
                Snackbar.make(binding.root, "Child Screen Lock is permanently exempt and allowed", Snackbar.LENGTH_SHORT).show()
            } else {
                appInfo.isAllowed = isChecked
            }
            saveAllowedAppsSilently()
        }
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
    }

    private fun saveAllowedAppsSilently() {
        if (appList.isEmpty()) return
        val selectedPackages = appList.filter { it.isAllowed }.map { it.packageName }.toSet() + packageName
        prefsManager.setAllowedPackages(selectedPackages)
    }

    private fun loadInstalledApps() {
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            val allowedSet = prefsManager.getAllowedPackages()
            val list = mutableListOf<AppInfo>()

            for (resolveInfo in resolveInfos) {
                val pkgName = resolveInfo.activityInfo.packageName
                if (pkgName == packageName) continue

                val appName = resolveInfo.loadLabel(pm).toString()
                val icon = resolveInfo.loadIcon(pm)
                val isAllowed = allowedSet.contains(pkgName)

                list.add(AppInfo(appName, pkgName, icon, isAllowed))
            }

            list.sortBy { it.appName.lowercase() }

            // Pin our own app at the very top as permanently allowed/exempt
            val ownIcon = try { packageManager.getApplicationIcon(packageName) } catch (e: Exception) { null }
            val ownAppInfo = AppInfo(
                appName = "Child Screen Lock (This App - Always Allowed)",
                packageName = packageName,
                icon = ownIcon,
                isAllowed = true
            )
            list.add(0, ownAppInfo)

            withContext(Dispatchers.Main) {
                appList.clear()
                appList.addAll(list)
                adapter.updateData(appList)
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun saveAllowedApps() {
        val selectedPackages = appList.filter { it.isAllowed }.map { it.packageName }.toSet() + packageName
        prefsManager.setAllowedPackages(selectedPackages)
        Snackbar.make(binding.root, "Saved ${selectedPackages.size} allowed apps", Snackbar.LENGTH_SHORT).show()
        finish()
    }
}
