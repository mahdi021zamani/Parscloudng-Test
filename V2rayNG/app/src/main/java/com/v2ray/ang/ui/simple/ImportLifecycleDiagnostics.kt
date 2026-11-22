package com.v2ray.ang.ui.simple

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Intent
import android.os.Build
import android.os.Process

/**
 * Read-only process and activity diagnostics for the personal-import backgrounding issue.
 * Never records config contents, clipboard data, URL query strings, or license values.
 */
object ImportLifecycleDiagnostics {
    fun install(application: Application) {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            application.packageName
        }
        // Keep the trace focused on the UI process, not VPN/native worker processes.
        if (processName != application.packageName) return

        ImportDiagnosticLog.record(
            application,
            "Process appOnCreate pid=${Process.myPid()} process=$processName package=${application.packageName} sdk=${Build.VERSION.SDK_INT}"
        )
        recordHistoricalExits(application)

        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: android.os.Bundle?) {
                val intent = activity.intent
                ImportDiagnosticLog.record(
                    application,
                    "Lifecycle created class=${activity.javaClass.name} pid=${Process.myPid()} process=$processName taskId=${activity.taskId} root=${activity.isTaskRoot} saved=${state != null} ${describeIntent(intent)}"
                )
            }

            override fun onActivityStarted(activity: Activity) = recordActivity(application, "started", activity)
            override fun onActivityResumed(activity: Activity) = recordActivity(application, "resumed", activity)
            override fun onActivityPaused(activity: Activity) = recordActivity(application, "paused", activity)
            override fun onActivityStopped(activity: Activity) = recordActivity(application, "stopped", activity)
            override fun onActivitySaveInstanceState(activity: Activity, state: android.os.Bundle) {
                recordActivity(application, "saveState", activity)
            }
            override fun onActivityDestroyed(activity: Activity) {
                ImportDiagnosticLog.record(
                    application,
                    "Lifecycle destroyed class=${activity.javaClass.name} pid=${Process.myPid()} taskId=${activity.taskId} finishing=${activity.isFinishing} changingConfig=${activity.isChangingConfigurations}"
                )
            }
        })

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (previousHandler != null) {
            Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                val frames = error.stackTrace.take(10).joinToString(" <- ") {
                    "${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})"
                }
                ImportDiagnosticLog.record(
                    application,
                    "Process uncaughtException pid=${Process.myPid()} thread=${thread.name} type=${error.javaClass.name} frames=$frames"
                )
                previousHandler.uncaughtException(thread, error)
            }
        }
    }

    private fun recordActivity(application: Application, event: String, activity: Activity) {
        ImportDiagnosticLog.record(
            application,
            "Lifecycle $event class=${activity.javaClass.name} pid=${Process.myPid()} taskId=${activity.taskId} root=${activity.isTaskRoot} finishing=${activity.isFinishing} changingConfig=${activity.isChangingConfigurations}"
        )
    }

    private fun describeIntent(intent: Intent?): String {
        if (intent == null) return "intent=null"
        val data = intent.data
        return "intentAction=${intent.action} intentFlags=0x${intent.flags.toString(16)} component=${intent.component?.className} scheme=${data?.scheme} host=${data?.host}"
    }

    private fun recordHistoricalExits(application: Application) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            ImportDiagnosticLog.record(application, "Process historicalExitInfo unsupported")
            return
        }
        runCatching {
            val manager = application.getSystemService(ActivityManager::class.java)
            val exits: List<ApplicationExitInfo> =
                manager.getHistoricalProcessExitReasons(application.packageName, 0, 8)
            if (exits.isEmpty()) {
                ImportDiagnosticLog.record(application, "Process historicalExitInfo none")
            } else {
                exits.forEach { exit ->
                    val description = exit.description.orEmpty()
                        .replace('\n', ' ')
                        .take(180)
                    ImportDiagnosticLog.record(
                        application,
                        "Process historicalExit pid=${exit.pid} process=${exit.processName} reason=${exit.reason} status=${exit.status} importance=${exit.importance} timestamp=${exit.timestamp} description=$description"
                    )
                }
            }
        }.onFailure {
            ImportDiagnosticLog.record(
                application,
                "Process historicalExitInfo error=${it.javaClass.simpleName}"
            )
        }
    }
}
