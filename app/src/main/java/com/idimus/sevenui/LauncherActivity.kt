package com.idimus.sevenui

import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel

class LauncherActivity : ComponentActivity() {
    private val scope = MainScope()
    private lateinit var state: LauncherState

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        state = LauncherState(applicationContext, scope)
        state.start()
        val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) dynamicDarkColorScheme(this)
        else darkColorScheme()
        state.requestDefaultHome = { requestHomeRole(fallbackToSettings = true) }
        widgetHost = LauncherWidgetHost(this).also { host ->
            host.onLongPress = { id, view, x, y ->
                val loc = IntArray(2)
                view.getLocationInWindow(loc)
                state.onWidgetLongPress(
                    id,
                    androidx.compose.ui.geometry.Offset(loc[0] + x, loc[1] + y),
                    androidx.compose.ui.geometry.Rect(
                        loc[0].toFloat(), loc[1].toFloat(),
                        (loc[0] + view.width).toFloat(), (loc[1] + view.height).toFloat(),
                    ),
                )
            }
        }
        state.widgetHost = widgetHost
        state.onAddWidget = ::addWidget
        state.onBackup = { backupFile.launch("7UI-backup.json") }
        state.onRestore = { restoreFile.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }
        state.onReconfigureWidget = { id ->
            runCatching { widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_RECONFIGURE, null) }
        }
        setContent {
            MaterialTheme(colorScheme = colors) {
                LauncherScreen(state)
            }
        }
        if (savedInstanceState == null) requestHomeRole(fallbackToSettings = false)
    }

    private lateinit var widgetHost: LauncherWidgetHost

    private val backupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@registerForActivityResult
        val ok = runCatching { Backup.write(this, uri) }.isSuccess
        Toast.makeText(this, getString(if (ok) R.string.backup_saved else R.string.backup_failed), Toast.LENGTH_SHORT).show()
    }

    // The launcher state is rebuilt from the restored files, so the activity starts over.
    private val restoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val result = runCatching { Backup.read(this, uri) }
        Toast.makeText(this, getString(if (result.isSuccess) R.string.restored else R.string.not_a_backup), Toast.LENGTH_SHORT).show()
        if (result.isSuccess) recreate()
    }
    private var pendingWidget: Pair<Int, AppWidgetProviderInfo>? = null

    override fun onStart() {
        super.onStart()
        widgetHost.startListening()
    }

    override fun onStop() {
        super.onStop()
        widgetHost.stopListening()
    }

    /** Bind (asking the user once per provider if needed), then configure, then place. */
    private fun addWidget(info: AppWidgetProviderInfo) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidget = id to info
        val manager = AppWidgetManager.getInstance(this)
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configureOrPlace(id, info)
        } else {
            bindWidget.launch(
                Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            )
        }
    }

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (id, info) = pendingWidget ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) configureOrPlace(id, info) else widgetHost.deleteAppWidgetId(id)
    }

    private fun configureOrPlace(id: Int, info: AppWidgetProviderInfo) {
        val optional = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (info.configure == null || optional) {
            state.placeWidget(id, info)
            return
        }
        try {
            widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIGURE, null)
        } catch (_: Exception) {
            state.placeWidget(id, info)
        }
    }

    // The host's configure API only reports back through the legacy result callback.
    @Deprecated("Needed for AppWidgetHost.startAppWidgetConfigureActivityForResult")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIGURE) return
        val (id, info) = pendingWidget ?: return
        pendingWidget = null
        if (resultCode == RESULT_OK) state.placeWidget(id, info) else widgetHost.deleteAppWidgetId(id)
    }

    private companion object {
        const val REQ_CONFIGURE = 1
        const val REQ_RECONFIGURE = 2
    }

    private var fallbackOnDeny = false

    private val homeRoleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        state.isDefaultHome = isDefaultHome()
        if (!state.isDefaultHome && fallbackOnDeny) {
            // After repeated refusals Android stops showing the role dialog, so the settings page is the only way left.
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    private fun roleManager() = getSystemService(RoleManager::class.java)

    private fun isDefaultHome() = roleManager().isRoleHeld(RoleManager.ROLE_HOME)

    /** Shows the system "Set 7UI as default home app?" dialog, as Nova does on first launch. */
    private fun requestHomeRole(fallbackToSettings: Boolean) {
        val rm = roleManager()
        state.isDefaultHome = isDefaultHome()
        if (!rm.isRoleAvailable(RoleManager.ROLE_HOME) || state.isDefaultHome) return
        fallbackOnDeny = fallbackToSettings
        homeRoleRequest.launch(rm.createRequestRoleIntent(RoleManager.ROLE_HOME))
    }

    override fun onResume() {
        super.onResume()
        state.isDefaultHome = isDefaultHome()
        // Also picks up the overlay permission when the user comes back from granting it.
        EdgePanelService.sync(this, state.settings.edgePanel)
    }

    // Pressing Home while already on the launcher arrives here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        state.onHomePressed()
        // Opened again from another launcher's app list while not yet the default.
        if (!isDefaultHome()) requestHomeRole(fallbackToSettings = false)
    }

    override fun onDestroy() {
        state.stop()
        scope.cancel()
        super.onDestroy()
    }
}
