package com.glyph.glyph_v3.data.service

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.glyph.glyph_v3.data.repo.PresenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import java.util.Collections
import java.lang.ref.WeakReference

/**
 * Tracks whether any Activity is currently resumed (user-visible).
 *
 * This is more reliable than process lifecycle for our use-case because:
 * - FCM can start the process for background work without any visible UI.
 * - We only want to play notification sound when the user is actively in the app.
 */
object AppVisibilityTracker {

    private const val OFFLINE_GRACE_MS = 400L

    private val resumedCount = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingOfflineJob: Job? = null

    // Keep a thread-safe list of weak references to created activities so we can
    // recreate them when the theme changes.
    private val activities = Collections.synchronizedList(mutableListOf<WeakReference<Activity>>())

    val isAppVisible: Boolean
        get() = resumedCount.get() > 0

    fun init(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    // Track created activities
                    activities.add(WeakReference(activity))
                }

                override fun onActivityResumed(activity: Activity) {
                    pendingOfflineJob?.cancel()
                    pendingOfflineJob = null

                    val visibleCount = resumedCount.incrementAndGet()
                    if (visibleCount == 1) {
                        runCatching {
                            WalkieTalkieManager.getInstance(activity.applicationContext).apply {
                                primeTransport("app_visible", forceTokenRefresh = true)
                                startWatchingForRequests()
                            }
                        }
                    }
                }

                override fun onActivityPaused(activity: Activity) {
                    while (true) {
                        val current = resumedCount.get()
                        if (current <= 0) {
                            resumedCount.set(0)
                            return
                        }
                        if (resumedCount.compareAndSet(current, current - 1)) {
                            if (current - 1 == 0) {
                                runCatching {
                                    WalkieTalkieManager.getInstance(activity.applicationContext)
                                        .stopWatchingForRequests()
                                }
                                pendingOfflineJob?.cancel()
                                pendingOfflineJob = scope.launch {
                                    delay(OFFLINE_GRACE_MS)
                                    if (resumedCount.get() == 0) {
                                        runCatching {
                                            PresenceManager.goOffline()
                                        }
                                    }
                                }
                            }
                            return
                        }
                    }
                }

                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

                override fun onActivityDestroyed(activity: Activity) {
                    // Remove any references to destroyed activities
                    synchronized(activities) {
                        activities.removeAll { ref -> ref.get() == null || ref.get() == activity }
                    }
                }
            }
        )
    }

    /**
     * Recreate all tracked activities so theme changes take effect immediately across the app.
     */
    fun recreateAllActivities() {
        val snapshot: List<Activity> = synchronized(activities) {
            activities.mapNotNull { it.get() }
        }
        for (activity in snapshot) {
            try {
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.recreate()
                }
            } catch (e: Exception) {
                // Ignore individual failures (activity may be in transient state)
            }
        }
    }
}
