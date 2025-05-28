package com.mopemope.urlfilter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Browser
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.net.toUri
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.analytics.logEvent
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.remoteconfig.ConfigUpdate
import com.google.firebase.remoteconfig.ConfigUpdateListener
import com.google.firebase.remoteconfig.FirebaseRemoteConfigException
import com.google.firebase.remoteconfig.remoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings

// Data class to hold browser configuration
data class SupportedBrowserConfig(val packageName: String, val addressBarId: String)

// Data class to hold filter configuration
data class FilterConfig(
    var restrictedAddress: Set<String>,
    var redirectTo: String,
    var lockAccessibilityService: Boolean,
)

// Constants
// this is the tag used for logging
const val TAG = "URLFilterService"

// this is the key used for remote config to fetch restricted addresses
const val RESTRICTED_ADDRESS = "restricted_address"

// this is the key used for remote config to fetch redirect URL
const val REDIRECT_TO = "redirect_to"

// this is the key used for remote config to fetch lock accessibility service
const val LOCK_ACCESSIBILITY_SERVICE = "lock_accessibility_service"

const val CHECK_EVENT = AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE + AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT

class URLFilterService : AccessibilityService() {
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val previousUrlDetections: HashMap<String, Long> = HashMap()
    private var packageName: String = ""
    private var foregroundAppName: String? = null
    private var installId: String = ""
    private var settingsPackage: String? = null

    private val filterConfig: FilterConfig =
        FilterConfig(
            restrictedAddress = setOf("google.com/search", "google.com/logos/", "www.google.com/logos/"),
            redirectTo = "https://example.com/",
            lockAccessibilityService = true,
        )

    companion object {
        var instance: URLFilterService? = null

        init {
            val remoteConfig = Firebase.remoteConfig
            val configSettings =
                remoteConfigSettings {
                    minimumFetchIntervalInSeconds = 60
                }
            remoteConfig.setConfigSettingsAsync(configSettings)
            remoteConfig.setDefaultsAsync(R.xml.remote_config_defaults)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        this.setupAnalytics()

        this.serviceInfo =
            AccessibilityServiceInfo().apply {
                eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                packageNames = packageNames()
                feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL
                notificationTimeout = 300
                flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            }
        Log.d(TAG, "Service connected.")
        // fetch remote config
        this.fetchAndActivateConfig()
    }

    private fun setupAnalytics() {
        firebaseAnalytics = Firebase.analytics
        FirebaseInstallations.getInstance().id.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d("Installations", "Installation ID: " + task.result)
                installId = task.result
                firebaseAnalytics.setUserProperty("installId", installId)
            } else {
                Log.e("Installations", "Unable to get Installation ID")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) {
            return
        }
        // Log.d(TAG, "event: $event")
        filterBrowserURL(event)
    }

    override fun onInterrupt() {
        // ignore
        Log.d(TAG, "interrupt called.")
    }

    fun filterBrowserURL(event: AccessibilityEvent) {
        try {
            // get accessibility node info
            val parentNodeInfo = event.source ?: return

            if (event.packageName != null) {
                this.packageName = event.packageName.toString()
            }
            // get foreground app name
            val packageManager: PackageManager = this.packageManager
            try {
                val applicationInfo = packageManager.getApplicationInfo(packageName, 0)
                foregroundAppName = packageManager.getApplicationLabel(applicationInfo) as String
            } catch (e: PackageManager.NameNotFoundException) {
                // e.printStackTrace()
            }
            // Log.d(TAG, "event: $event")
            // Log.d(TAG, "event: " + event.contentChangeTypes)
            // Log.d(TAG, "event: ${event.text}")

            // get all the child views from the nodeInfo
            this.getChild(parentNodeInfo)

            if (this.checkOpenAccessibilitySetting(event)) {
                return
            }

            // fetch urls from different browsers
            var browserConfig: SupportedBrowserConfig? = null
            for (supportedConfig in getSupportedBrowsers()) {
                if (supportedConfig.packageName == packageName) {
                    browserConfig = supportedConfig
                }
            }
            // this is not supported browser, so exit
            if (browserConfig == null) {
                return
            }

            val capturedUrl =
                this.captureUrl(parentNodeInfo, browserConfig)
            parentNodeInfo.recycle()

            // we can't find a url. Browser either was updated or opened page without url text field
            if (capturedUrl == null) {
                return
            }

            val eventTime = event.eventTime
            val detectionId = "$packageName, and url $capturedUrl"
            val lastRecordedTime: Long? =
                if (previousUrlDetections.containsKey(detectionId)) {
                    previousUrlDetections[detectionId]
                } else {
                    0
                }
            // some kind of redirect throttling
            val changeTypes = event.contentChangeTypes
            val delta = eventTime - lastRecordedTime!!
            if (changeTypes and CHECK_EVENT == CHECK_EVENT) {
                if (delta >= 1000) {
                    val remoteConfig = Firebase.remoteConfig
                    remoteConfig.activate()
                    previousUrlDetections[detectionId] = eventTime
                    val ret =
                        this.analyzeCapturedUrl(
                            capturedUrl,
                            browserConfig.packageName,
                        )
                }
            }
        } catch (e: Exception) {
            // ignored
        }
    }

    private fun checkOpenAccessibilitySetting(event: AccessibilityEvent): Boolean {
        if (filterConfig.lockAccessibilityService && event.packageName == this.settingsPackage &&
            !event.text.isEmpty() && event.text[0] == "ユーザー補助"
        ) {
            try {
                val intent = Intent(Settings.ACTION_SETTINGS)
                intent.addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
                this.startActivity(intent)
                Log.d(TAG, "Settings activity started.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start activity: ${e.message}")
            }
            return true
        }
        return false
    }

    private fun fetchAndActivateConfig() {
        Log.d(TAG, "Fetching remote config...")
        val remoteConfig = Firebase.remoteConfig
        remoteConfig.fetchAndActivate().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Config params updated: ${remoteConfig.all}")
                this.filterConfig.restrictedAddress =
                    remoteConfig.getString(RESTRICTED_ADDRESS)
                        .split(",")
                        .map { it.trim() }
                        .toSet()
                this.filterConfig.redirectTo = remoteConfig.getString(REDIRECT_TO).trim()
                this.filterConfig.lockAccessibilityService = remoteConfig.getBoolean(LOCK_ACCESSIBILITY_SERVICE)
                Log.d(TAG, "Remote Config: ${this.filterConfig}")
            } else {
                Log.e(TAG, "Failed to fetch config: ${task.exception}")
            }
        }

        remoteConfig.addOnConfigUpdateListener(
            object : ConfigUpdateListener {
                override fun onUpdate(configUpdate: ConfigUpdate) {
                    Log.d(TAG, "Firebase Config Updated keys: " + configUpdate.updatedKeys)
                    remoteConfig.activate().addOnCompleteListener {
                        filterConfig.restrictedAddress =
                            remoteConfig.getString(RESTRICTED_ADDRESS)
                                .split(",")
                                .map { it.trim() }
                                .toSet()
                        filterConfig.redirectTo = remoteConfig.getString(REDIRECT_TO).trim()
                        filterConfig.lockAccessibilityService = remoteConfig.getBoolean(LOCK_ACCESSIBILITY_SERVICE)
                        Log.d(TAG, "Remote Config: $filterConfig")
                    }
                }

                override fun onError(error: FirebaseRemoteConfigException) {
                    Log.w(TAG, "Config update error with code: " + error.code, error)
                }
            },
        )
    }

    private fun getChild(info: AccessibilityNodeInfo) {
        val i = info.childCount
        for (p in 0 until i) {
            val n = info.getChild(p)
            if (n != null) {
                n.viewIdResourceName
                if (n.text != null) {
                    n.text.toString()
                }
                getChild(n)
            }
        }
    }

    private fun captureUrl(
        info: AccessibilityNodeInfo,
        config: SupportedBrowserConfig?,
    ): String? {
        if (config == null) return null
        val nodes = info.findAccessibilityNodeInfosByViewId(config.addressBarId)
        if (nodes == null || nodes.isEmpty()) {
            return null
        }
        val addressBarNodeInfo = nodes[0]
        var url: String? = null
        if (addressBarNodeInfo.text != null) {
            url = addressBarNodeInfo.text.toString()
        }
        addressBarNodeInfo.recycle()
        return url
    }

    private fun analyzeCapturedUrl(
        capturedUrl: String,
        browserPackage: String,
    ): Boolean {
        Log.d(TAG, "check url: $capturedUrl")
        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SEARCH) {
            param(FirebaseAnalytics.Param.SEARCH_TERM, capturedUrl)
        }
        for (url in filterConfig.restrictedAddress) {
            if (capturedUrl.lowercase().contains(url)) {
                val replaced = filterConfig.redirectTo
                if (this.performRedirect(replaced, browserPackage)) {
                    Log.d(TAG, "$capturedUrl redirect to: ${filterConfig.redirectTo}")
                    return true
                }
            }
        }
        return false
    }

    private fun performRedirect(
        redirectUrl: String,
        browserPackage: String,
    ): Boolean {
        var url = redirectUrl
        if (!redirectUrl.startsWith("https://")) {
            url = "https://$redirectUrl"
        }
        if (url == "") {
            return false
        }

        try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            intent.setPackage(browserPackage)
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, browserPackage)
            intent.addFlags(
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            this.startActivity(intent)
            return true
        } catch (e: ActivityNotFoundException) {
            // the expected browser is not installed
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            this.startActivity(intent)
        }
        return false
    }

    private fun querySettingPkgName(): String? {
        if (this.settingsPackage != null) {
            return this.settingsPackage
        }
        val intent = Intent(Settings.ACTION_SETTINGS)
        val resolveInfos: MutableList<ResolveInfo?>? =
            this.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolveInfos == null || resolveInfos.isEmpty()) {
            return ""
        }

        val name = resolveInfos.get(0)!!.activityInfo.packageName
        if (name != null && !name.isEmpty()) {
            this.settingsPackage = name
        }
        return name
    }

    private fun packageNames(): Array<String> {
        val packageNames: MutableList<String> = ArrayList()
        for (config in getSupportedBrowsers()) {
            packageNames.add(config.packageName)
        }
        var settingPkgName = querySettingPkgName()
        if (settingPkgName == null || settingPkgName.isEmpty()) {
            settingPkgName = "com.android.settings"
        }
        packageNames.add(settingPkgName)
        return packageNames.toTypedArray()
    }
}

private fun getSupportedBrowsers(): List<SupportedBrowserConfig> {
    val browsers: MutableList<SupportedBrowserConfig> = ArrayList()
    browsers.add(
        SupportedBrowserConfig(
            "com.android.chrome",
            "com.android.chrome:id/url_bar",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "org.mozilla.firefox",
            "org.mozilla.firefox:id/mozac_browser_toolbar_url_view",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "com.opera.browser",
            "com.opera.browser:id/url_field",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "com.opera.mini.native",
            "com.opera.mini.native:id/url_field",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "com.duckduckgo.mobile.android",
            "com.duckduckgo.mobile.android:id/omnibarTextInput",
        ),
    )

    browsers.add(
        SupportedBrowserConfig(
            "com.microsoft.emmx",
            "com.microsoft.emmx:id/url_bar",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "com.coloros.browser",
            "com.coloros.browser:id/azt",
        ),
    )
    browsers.add(
        SupportedBrowserConfig(
            "com.sec.android.app.sbrowser",
            "com.sec.android.app.sbrowser:id/location_bar_edit_text",
        ),
    )
    return browsers
}
