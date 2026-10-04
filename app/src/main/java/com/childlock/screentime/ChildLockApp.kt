package com.childlock.screentime

import android.app.Activity
import android.app.Application
import android.os.Bundle

class ChildLockApp : Application() {

    companion object {
        @Volatile
        var isAppInForeground: Boolean = false
            private set
    }

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                isAppInForeground = true
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    isAppInForeground = false
                }
            }

            override fun onActivityResumed(activity: Activity) {
                isAppInForeground = true
            }

            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
