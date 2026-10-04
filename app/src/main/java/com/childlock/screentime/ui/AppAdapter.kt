package com.childlock.screentime.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.childlock.screentime.data.AppInfo
import com.childlock.screentime.databinding.ItemAppBinding

class AppAdapter(
    private var appsList: List<AppInfo>,
    private val onToggleChanged: (AppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppAdapter.AppViewHolder>() {

    private var filteredList: List<AppInfo> = appsList

    inner class AppViewHolder(val binding: ItemAppBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val app = filteredList[position]
        holder.binding.tvAppName.text = app.appName
        holder.binding.tvPackageName.text = app.packageName
        if (app.icon != null) {
            holder.binding.ivAppIcon.setImageDrawable(app.icon)
        }
        
        holder.binding.switchAllowed.setOnCheckedChangeListener(null)
        holder.binding.switchAllowed.isChecked = app.isAllowed

        holder.binding.switchAllowed.setOnCheckedChangeListener { _, isChecked ->
            app.isAllowed = isChecked
            onToggleChanged(app, isChecked)
        }
    }

    override fun getItemCount(): Int = filteredList.size

    fun updateData(newList: List<AppInfo>) {
        appsList = newList
        filteredList = newList
        notifyDataSetChanged()
    }

    fun filter(query: String) {
        filteredList = if (query.isBlank()) {
            appsList
        } else {
            appsList.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
        }
        notifyDataSetChanged()
    }
}
