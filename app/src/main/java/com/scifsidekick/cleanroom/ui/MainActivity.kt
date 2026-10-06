package com.scifsidekick.cleanroom.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.scifsidekick.cleanroom.AppGraph
import com.scifsidekick.cleanroom.service.ForwardingService
import com.scifsidekick.cleanroom.util.RemoteControlCodec
import com.scifsidekick.cleanroom.util.suspendRunCatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private var notificationAccessGranted by mutableStateOf(false)
    private var batteryUnrestricted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (PrivacyPreferences(this).hideInRecents) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        }
        refreshNotificationAccess()
        refreshBatteryStatus()
        runCatching { ForwardingService.start(this) }
            .onFailure { failure ->
                lifecycleScope.launch {
                    AppGraph.from(this@MainActivity).repository.recordServiceEvent(
                        "Foreground service could not start from the activity: ${failure.message}",
                    )
                }
            }
        setContent { SidekickApp(viewModel) }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationAccess()
        refreshBatteryStatus()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SidekickApp(vm: MainViewModel) {
        val state by vm.state.collectAsStateWithLifecycle()
        val events by vm.events.collectAsStateWithLifecycle()
        val queued by vm.queued.collectAsStateWithLifecycle()
        val filters by vm.filters.collectAsStateWithLifecycle()
        val appSettings by vm.appSettings.collectAsStateWithLifecycle()
        val lastSentAt by vm.lastSentAt.collectAsStateWithLifecycle()
        val snackbar = remember { SnackbarHostState() }
        val appearancePreferences = remember { AppearancePreferences(this@MainActivity) }
        val privacyPreferences = remember { PrivacyPreferences(this@MainActivity) }
        var hideInRecents by remember { mutableStateOf(privacyPreferences.hideInRecents) }
        LaunchedEffect(hideInRecents) {
            if (hideInRecents) {
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        var themeMode by rememberSaveable { mutableStateOf(appearancePreferences.themeMode()) }
        val initialAccent = remember { appearancePreferences.accentSelection() }
        var accentPresetKey by
            rememberSaveable {
                mutableStateOf(
                    when (initialAccent) {
                        is AccentSelection.Custom -> "custom"
                        is AccentSelection.RainbowRoad -> "rainbow_road"
                        is AccentSelection.Preset -> initialAccent.choice.storageKey
                    },
                )
            }
        var rainbowRoadUnlocked by rememberSaveable { mutableStateOf(appearancePreferences.rainbowRoadUnlocked()) }
        // The last color the user actually picked in the wheel, independent of whether a preset
        // or custom accent is currently active -- read from AppearancePreferences.lastCustomColor
        // (not from `initialAccent`, which only reflects whatever was active on cold start) so
        // that picking a custom color, switching to a preset, and switching back all restore the
        // exact same color instead of forgetting it. Null only before any custom color has ever
        // been picked.
        var customAccentArgb by
            rememberSaveable { mutableStateOf(appearancePreferences.lastCustomColor()?.toArgb()) }
        // A local val, not the `by`-delegated property itself, is what makes the null check
        // below smart-cast cleanly: Kotlin can't smart-cast a mutableStateOf-backed `var`
        // (the compiler can't prove a custom property delegate's getter is stable across the
        // check), so reading it into a local first is what avoids needing a `!!` afterward.
        val customColorArgb = customAccentArgb
        val accent: AccentSelection =
            if (accentPresetKey == "custom" && customColorArgb != null) {
                AccentSelection.Custom(Color(customColorArgb))
            } else if (accentPresetKey == "rainbow_road") {
                AccentSelection.RainbowRoad
            } else {
                AccentSelection.Preset(AccentChoice.fromStorage(accentPresetKey))
            }
        var showColorPicker by remember { mutableStateOf(false) }
        var oauthAuthorized by rememberSaveable { mutableStateOf(vm.oauthAuthorized) }
        var pubSubGranted by remember { mutableStateOf(vm.pubSubGranted) }
        var gmailAccountEmail by rememberSaveable { mutableStateOf<String?>(null) }
        var currentScreenName by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
        var appMenuExpanded by remember { mutableStateOf(false) }
        var editingFilterId by rememberSaveable { mutableStateOf<Long?>(null) }
        val currentScreen = AppScreen.valueOf(currentScreenName)
        fun goHome() {
            currentScreenName = AppScreen.HOME.name
        }

        LaunchedEffect(oauthAuthorized) {
            gmailAccountEmail = if (oauthAuthorized) vm.fetchGmailAccountEmail() else null
            gmailAccountEmail?.let { vm.seedRemoteControlOwnerIfEmpty(it) }
        }

        val permissionLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions(),
            ) { result ->
                val denied = result.filterValues { !it }.keys
                if (denied.isNotEmpty()) vm.messages.tryEmit("Some permissions were denied; forwarding may be incomplete")
                promptBatteryExemptionOnce()
            }
        val authLauncher =
            rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                    lifecycleScope.launch {
                        runCatching { AppGraph.from(this@MainActivity).oauth.consumeAuthorizationResult(result.data) }
                            .onSuccess {
                                vm.onGmailConnected()
                                oauthAuthorized = true
                                pubSubGranted = vm.pubSubGranted
                            }.onFailure { vm.messages.emit("Gmail authorization failed: ${it.message}") }
                    }
                } else {
                    vm.messages.tryEmit("Gmail connection canceled")
                }
            }
        // Stashes the passphrase typed on the Settings screen between "user tapped Export" and
        // "the SAF picker came back with a destination uri" -- same reason pendingLogExport
        // exists below, and cleared immediately after use so it doesn't linger in memory.
        var pendingExportPassphrase by remember { mutableStateOf<String?>(null) }
        val exportLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val passphrase = pendingExportPassphrase
                pendingExportPassphrase = null
                if (uri == null) return@rememberLauncherForActivityResult
                lifecycleScope.launch {
                    suspendRunCatching {
                        val json = vm.exportBackup(passphrase)
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                        }
                    }.onSuccess { vm.messages.emit("Backup saved") }
                        .onFailure { vm.messages.emit("Backup failed: ${it.message}") }
                }
            }
        // Holds a just-picked backup file's raw text between "it turned out to be passphrase-
        // protected" and "the user typed the right passphrase into the dialog below" -- non-null
        // is exactly what drives that dialog being shown.
        var pendingImportText by remember { mutableStateOf<String?>(null) }
        var importPassphraseError by remember { mutableStateOf<String?>(null) }
        val importLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri == null) return@rememberLauncherForActivityResult
                lifecycleScope.launch {
                    // File reads are genuinely blocking I/O, not suspend-cancellable network
                    // calls -- the fix there isn't rethrowing cancellation, it's not running them
                    // on the UI-associated dispatcher in the first place.
                    val text =
                        withContext(Dispatchers.IO) {
                            runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                        }
                    if (text == null) {
                        vm.messages.emit("Could not read that file")
                    } else if (vm.isBackupEncrypted(text)) {
                        importPassphraseError = null
                        pendingImportText = text
                    } else {
                        vm.importBackup(text, null) {}
                    }
                }
            }
        // Stashes the window/toggle chosen on the History screen between "user tapped Export" and
        // "the SAF picker came back with a destination uri" -- CreateDocument's contract has no
        // way to carry caller-supplied arguments through that round trip itself.
        var pendingLogExport by remember { mutableStateOf<Triple<Long, Long, Boolean>?>(null) }
        val exportLogLauncher =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
                val request = pendingLogExport
                pendingLogExport = null
                if (uri == null || request == null) return@rememberLauncherForActivityResult
                val (startMs, endMs, includeBody) = request
                lifecycleScope.launch {
                    suspendRunCatching {
                        val csv = vm.exportMessageLog(startMs, endMs, includeBody)
                        withContext(Dispatchers.IO) {
                            contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                        }
                    }.onSuccess { vm.messages.emit("Message log exported") }
                        .onFailure { vm.messages.emit("Export failed: ${it.message}") }
                }
            }

        fun requestMissingPermissions() {
            val permissions =
                buildList {
                    add(Manifest.permission.RECEIVE_SMS)
                    add(Manifest.permission.SEND_SMS)
                    add(Manifest.permission.RECEIVE_MMS)
                    add(Manifest.permission.RECEIVE_WAP_PUSH)
                    add(Manifest.permission.READ_SMS)
                    add(Manifest.permission.READ_CONTACTS)
                    if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                }.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
            if (permissions.isNotEmpty()) {
                permissionLauncher.launch(permissions.toTypedArray())
            } else {
                promptBatteryExemptionOnce()
            }
        }
        LaunchedEffect(Unit) { requestMissingPermissions() }
        LaunchedEffect(Unit) { vm.messages.collectLatest { snackbar.showSnackbar(it) } }

        SidekickTheme(themeMode = themeMode, accent = accent, fontScale = FontScale.fromKey(appSettings.fontScaleKey)) {
            AppLockGate(enabled = appSettings.appLockEnabled) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    when (currentScreen) {
                                        AppScreen.HOME -> "SCIF Sidekick"
                                        AppScreen.FILTERS -> "Filters"
                                        AppScreen.FILTER_EDITOR -> "Edit filter"
                                        AppScreen.ACTIVITY -> "Activity"
                                        AppScreen.SETTINGS -> "Settings"
                                        AppScreen.COMMANDS -> "Commands"
                                        AppScreen.ABOUT -> "About"
                                        AppScreen.DEVELOPER -> "Developer tools"
                                    },
                                )
                            },
                            navigationIcon = {
                                if (currentScreen == AppScreen.HOME) {
                                    Box {
                                        IconButton(onClick = { appMenuExpanded = true }) {
                                            HamburgerGlyph()
                                        }
                                        DropdownMenu(
                                            expanded = appMenuExpanded,
                                            onDismissRequest = { appMenuExpanded = false },
                                        ) {
                                            listOf(
                                                "Filters" to AppScreen.FILTERS,
                                                "Activity" to AppScreen.ACTIVITY,
                                                "Settings" to AppScreen.SETTINGS,
                                                "Commands" to AppScreen.COMMANDS,
                                                "About" to AppScreen.ABOUT,
                                            ).forEach { (label, screen) ->
                                                DropdownMenuItem(
                                                    text = { Text(label) },
                                                    onClick = {
                                                        appMenuExpanded = false
                                                        currentScreenName = screen.name
                                                    },
                                                )
                                            }
                                            if (vm.isDebug) {
                                                DropdownMenuItem(
                                                    text = { Text("Developer safety tests") },
                                                    onClick = {
                                                        appMenuExpanded = false
                                                        currentScreenName = AppScreen.DEVELOPER.name
                                                    },
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    TextButton(onClick = {
                                        if (currentScreen == AppScreen.FILTER_EDITOR) {
                                            currentScreenName = AppScreen.FILTERS.name
                                        } else {
                                            goHome()
                                        }
                                    }) { Text("Back") }
                                }
                            },
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbar) },
                ) { padding ->
                    when (currentScreen) {
                        AppScreen.DEVELOPER -> {
                            val listState = rememberLazyListState()
                            listState.reportScrollActivity()
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                item { Spacer(Modifier.height(2.dp)) }
                                item { DebugCard(vm) }
                                item { Spacer(Modifier.height(24.dp)) }
                            }
                        }

                        AppScreen.SETTINGS -> {
                            val scrollState = rememberScrollState()
                            scrollState.reportScrollActivity()
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(scrollState)
                                        .padding(horizontal = 16.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Spacer(Modifier.height(2.dp))
                                    SectionHeader("Account & connectivity")
                                    GmailCard(
                                        oauthAuthorized = oauthAuthorized,
                                        accountEmail = gmailAccountEmail,
                                        showPushGrant = appSettings.gmailPushEnabled && !pubSubGranted,
                                        onConnect = {
                                            lifecycleScope.launch {
                                                suspendRunCatching {
                                                    AppGraph.from(this@MainActivity).oauth.beginAuthorization(this@MainActivity)
                                                }.onSuccess { step ->
                                                    if (step.pendingIntent != null) {
                                                        authLauncher.launch(IntentSenderRequest.Builder(step.pendingIntent.intentSender).build())
                                                    } else {
                                                        vm.onGmailConnected()
                                                        oauthAuthorized = true
                                                        pubSubGranted = vm.pubSubGranted
                                                    }
                                                }.onFailure { vm.messages.emit("Gmail authorization failed: ${it.message}") }
                                            }
                                        },
                                        onDisconnect = {
                                            vm.disconnectGmail()
                                            oauthAuthorized = false
                                            pubSubGranted = false
                                            gmailAccountEmail = null
                                        },
                                    )
                                    NotificationCoverageCard(
                                        accessGranted = notificationAccessGranted,
                                        onOpenSettings = ::openNotificationAccessSettings,
                                    )
                                    OutlinedButton(onClick = { requestBatteryExemption() }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Allow reliable background operation")
                                    }

                                    AppearanceCard(
                                        themeMode = themeMode,
                                        accent = accent,
                                        rememberedCustomColor = customAccentArgb?.let { Color(it) },
                                        fontScale = FontScale.fromKey(appSettings.fontScaleKey),
                                        rainbowRoadUnlocked = rainbowRoadUnlocked,
                                        onThemeMode = {
                                            themeMode = it
                                            appearancePreferences.saveThemeMode(it)
                                        },
                                        onPresetAccent = { choice ->
                                            accentPresetKey = choice.storageKey
                                            appearancePreferences.saveAccentSelection(AccentSelection.Preset(choice))
                                        },
                                        onRainbowRoadAccent = {
                                            accentPresetKey = "rainbow_road"
                                            appearancePreferences.saveAccentSelection(AccentSelection.RainbowRoad)
                                        },
                                        onOpenCustomPicker = { showColorPicker = true },
                                        onFontScale = { scale -> vm.updateAppSettings { it.copy(fontScaleKey = scale.key) } },
                                        onRainbowRoadUnlocked = {
                                            rainbowRoadUnlocked = true
                                            appearancePreferences.unlockRainbowRoad()
                                            vm.messages.tryEmit("🌈 Rainbow Road unlocked. Pick it under Accent color.")
                                        },
                                    )

                                    SectionHeader("Behavior")
                                    AppSettingsScreenBody(
                                        settings = appSettings,
                                        onChange = { updated -> vm.updateAppSettings { updated } },
                                        onDisablePush = vm::disablePush,
                                        onSendTestReceipt = vm::sendTestReceipt,
                                        hideInRecents = hideInRecents,
                                        onHideInRecentsChange = {
                                            hideInRecents = it
                                            privacyPreferences.hideInRecents = it
                                        },
                                    )

                                    SectionHeader("Data")
                                    BackupRestoreScreenBody(
                                        onExport = { passphrase ->
                                            pendingExportPassphrase = passphrase
                                            exportLauncher.launch("scif-sidekick-backup.json")
                                        },
                                        onImport = { importLauncher.launch(arrayOf("application/json")) },
                                    )

                                    Spacer(Modifier.height(24.dp))
                                }
                            }
                        }

                        AppScreen.ACTIVITY ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                ActivityScreen(
                                    events = events,
                                    onExport = { startMs, endMs, includeBody ->
                                        pendingLogExport = Triple(startMs, endMs, includeBody)
                                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                                        exportLogLauncher.launch("scif-sidekick-log_$stamp.csv")
                                    },
                                    onSendTestMessage = { type, sender, body, images -> vm.sendTestMessage(type, sender, body, images) },
                                    onTestConnectivity = vm::testGmailConnectivity,
                                    onTestPush = vm::testPushSetup,
                                )
                            }

                        AppScreen.COMMANDS ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                CommandsScreenBody(
                                    remoteControlEnabled = appSettings.remoteControlEnabled,
                                    authorizedAddressCount =
                                        RemoteControlCodec.fromJson(appSettings.remoteControlSendersJson).size,
                                )
                            }

                        AppScreen.ABOUT ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                AboutScreenBody()
                            }

                        AppScreen.FILTERS ->
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                FiltersListScreen(
                                    filters = filters,
                                    onOpen = { id ->
                                        editingFilterId = id
                                        currentScreenName = AppScreen.FILTER_EDITOR.name
                                    },
                                    onCreate = {
                                        vm.createFilter("New filter") { id ->
                                            editingFilterId = id
                                            currentScreenName = AppScreen.FILTER_EDITOR.name
                                        }
                                    },
                                    onReorder = vm::reorderFilters,
                                    onToggleEnabled = vm::setFilterEnabled,
                                    onDuplicate = { id ->
                                        vm.duplicateFilter(id) { newId ->
                                            editingFilterId = newId
                                            currentScreenName = AppScreen.FILTER_EDITOR.name
                                        }
                                    },
                                    onDeleteMultiple = { ids -> vm.deleteFilters(ids) },
                                )
                            }

                        AppScreen.FILTER_EDITOR -> {
                            val target = filters.firstOrNull { it.id == editingFilterId }
                            Box(Modifier.fillMaxSize().padding(padding)) {
                                if (target != null) {
                                    FilterEditorScreen(
                                        filter = target,
                                        isValidEmail = vm::isValidEmail,
                                        onSave = { updated ->
                                            vm.updateFilter(updated) { currentScreenName = AppScreen.FILTERS.name }
                                        },
                                        onDelete = {
                                            vm.deleteFilter(target.id) { currentScreenName = AppScreen.FILTERS.name }
                                        },
                                    )
                                }
                            }
                        }

                        AppScreen.HOME ->
                            HomeScreen(
                                padding = padding,
                                state = state,
                                queued = queued,
                                lastSentAt = lastSentAt,
                                filters = filters,
                                events = events,
                                smsPermissionsGranted = hasSmsPermissions(),
                                gmailConnected = oauthAuthorized,
                                notificationAccessGranted = notificationAccessGranted,
                                batteryUnrestricted = batteryUnrestricted,
                                onGrantPermissions = { requestMissingPermissions() },
                                onOpenNotificationAccess = ::openNotificationAccessSettings,
                                onRequestBatteryExemption = { requestBatteryExemption() },
                                onNavigate = { currentScreenName = it.name },
                                vm = vm,
                            )
                    }
                }

                if (showColorPicker) {
                    ColorWheelDialog(
                        // Seeded from the last color the user actually picked, not from whatever
                        // accent happens to be active right now -- opening the wheel to tweak an
                        // existing custom color must start from that color, even while a preset
                        // is currently selected, not from the preset's own color.
                        initialColor = customAccentArgb?.let { Color(it) } ?: accent.primaryFor(isSystemInDarkTheme()),
                        onDismiss = { showColorPicker = false },
                        onConfirm = { color ->
                            customAccentArgb = color.toArgb()
                            accentPresetKey = "custom"
                            appearancePreferences.saveAccentSelection(AccentSelection.Custom(color))
                            showColorPicker = false
                        },
                    )
                }

                if (pendingImportText != null) {
                    ImportPassphraseDialog(
                        error = importPassphraseError,
                        onDismiss = {
                            pendingImportText = null
                            importPassphraseError = null
                        },
                        onConfirm = { passphrase ->
                            val text = pendingImportText
                            if (text != null) {
                                vm.importBackup(text, passphrase) { result ->
                                    result
                                        .onSuccess {
                                            pendingImportText = null
                                            importPassphraseError = null
                                        }.onFailure {
                                            importPassphraseError = "Incorrect passphrase"
                                        }
                                }
                            }
                        },
                    )
                }

            }
        }
    }

    private fun hasSmsPermissions(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (!power.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName")),
                )
            }.onFailure {
                viewModel.messages.tryEmit("Open Android battery settings and set SCIF Sidekick to unrestricted")
            }
        }
    }

    private fun openNotificationAccessSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            .onFailure {
                viewModel.messages.tryEmit("Open Android Settings and allow Notification access for SCIF Sidekick")
            }
    }

    private fun refreshNotificationAccess() {
        notificationAccessGranted =
            NotificationManagerCompat
                .getEnabledListenerPackages(this)
                .contains(packageName)
    }

    private fun refreshBatteryStatus() {
        batteryUnrestricted = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
    }

    private fun promptBatteryExemptionOnce() {
        val setup = getSharedPreferences("first_run_setup", MODE_PRIVATE)
        if (!setup.getBoolean("battery_prompted", false)) {
            setup.edit().putBoolean("battery_prompted", true).apply()
            requestBatteryExemption()
        }
    }
}

internal enum class AppScreen {
    HOME,
    FILTERS,
    FILTER_EDITOR,
    ACTIVITY,
    SETTINGS,
    COMMANDS,
    ABOUT,
    DEVELOPER,
}

@Composable
private fun HamburgerGlyph() {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(
        Modifier
            .size(24.dp)
            .semantics { contentDescription = "Open navigation menu" },
    ) {
        val strokeWidth = 2.dp.toPx()
        listOf(0.28f, 0.5f, 0.72f).forEach { yFraction ->
            drawLine(
                color = color,
                start = Offset(size.width * 0.2f, size.height * yFraction),
                end = Offset(size.width * 0.8f, size.height * yFraction),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun ImportPassphraseDialog(
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var passphrase by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Passphrase-protected backup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This backup was protected with a passphrase. Enter it to restore.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(passphrase) }, enabled = passphrase.isNotBlank()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun GmailCard(
    oauthAuthorized: Boolean,
    accountEmail: String?,
    showPushGrant: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gmail connection", fontWeight = FontWeight.Bold)
            if (oauthAuthorized) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("CONNECTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(accountEmail ?: "Gmail connected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (showPushGrant) {
                    Text(
                        "Gmail push (beta) is on but hasn't been granted Pub/Sub access yet; replies use the normal poll until it is.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Grant push access") }
                }
                TextButton(onClick = onDisconnect, modifier = Modifier.fillMaxWidth()) { Text("Forget Gmail connection") }
            } else {
                Text("Connect Gmail to deliver forwarded mail for every filter.", style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("Connect Gmail") }
            }
        }
    }
}

/** A plain section title used to group cards on the merged Settings screen -- Account &
 *  connectivity / Appearance / Behavior / Data -- without needing separate screens for each. */
@Composable
private fun SectionHeader(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun NotificationCoverageCard(
    accessGranted: Boolean,
    onOpenSettings: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("RCS coverage", fontWeight = FontWeight.Bold)
            Text(
                if (accessGranted) {
                    "Notification access is enabled. Keep RCS turned on; live Google Messages and Samsung Messages notifications can be forwarded."
                } else {
                    "Keep RCS turned on. Allow notification access so Sidekick can forward live RCS messages that Android does not expose as SMS broadcasts."
                },
            )
            Text(
                "Only newly posted messaging notifications are processed. Muted, blocked, or hidden notifications may be unavailable, RCS media may be described without the original file, and Android 17 may delay some OTP SMS broadcasts.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                Text(if (accessGranted) "Review notification access" else "Allow notification access")
            }
        }
    }
}

@Composable
private fun AppearanceCard(
    themeMode: ThemeMode,
    accent: AccentSelection,
    fontScale: FontScale,
    rememberedCustomColor: Color?,
    rainbowRoadUnlocked: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onPresetAccent: (AccentChoice) -> Unit,
    onRainbowRoadAccent: () -> Unit,
    onOpenCustomPicker: () -> Unit,
    onFontScale: (FontScale) -> Unit,
    onRainbowRoadUnlocked: () -> Unit,
) {
    // Undisclosed on purpose -- tapping the card title seven times unlocks Rainbow Road, the same
    // "tap the build number" gag Android itself uses for Developer options. Resets if you stop
    // partway; there is no partial-progress indicator, or it wouldn't be a secret.
    var titleTapCount by remember { mutableStateOf(0) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Appearance",
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier.clickable(enabled = !rainbowRoadUnlocked) {
                        titleTapCount++
                        if (titleTapCount >= 7) {
                            titleTapCount = 0
                            onRainbowRoadUnlocked()
                        }
                    },
            )
            Text("Theme", style = MaterialTheme.typography.bodySmall)
            // A real grid, columns aligned edge-to-edge like the original design -- not a
            // wrapping row of independently-sized pills, which read as unaligned "puzzle
            // pieces" once labels of different lengths sat next to each other. The column count
            // itself steps down as text size grows, which is what actually prevents a
            // single-word label like "System" from being squeezed narrower than its own text:
            // fewer, wider columns rather than same-width text crammed into a fixed column.
            SelectableButtonGrid(ThemeMode.entries, columnsFor(fontScale, 3), { it.label }, { it == themeMode }, onThemeMode)
            Text("Accent color", style = MaterialTheme.typography.bodySmall)
            SelectableButtonGrid(
                AccentChoice.entries,
                columnsFor(fontScale, 3),
                { it.label },
                { accent is AccentSelection.Preset && accent.choice == it },
                onPresetAccent,
            )
            if (rainbowRoadUnlocked) {
                SelectableButton(
                    label = "Rainbow Road 🌈",
                    selected = accent is AccentSelection.RainbowRoad,
                    onClick = onRainbowRoadAccent,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // A previously-picked custom color is never lost just by selecting a preset --
            // AppearancePreferences keeps it independent of which accent is currently active --
            // so this always shows the last color actually picked, one single full-width
            // button matching every other row's alignment. Tapping it reopens the wheel seeded
            // with that exact remembered color (never with whatever preset happens to be active),
            // so confirming without changing anything reselects it in one extra tap; a second,
            // separate "Edit" affordance was tried and made the row visibly uneven against the
            // grid above and below it for no real gain over just tapping the one button again.
            SelectableButton(
                label = rememberedCustomColor?.let { "Custom (${it.toHexString()})" } ?: "Custom…",
                selected = accent is AccentSelection.Custom,
                onClick = onOpenCustomPicker,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Text size", style = MaterialTheme.typography.bodySmall)
            SelectableButtonGrid(FontScale.entries, 2, { it.label }, { it == fontScale }, onFontScale)
        }
    }
}

/** Renders [items] as a grid of [SelectableButton]s, [columns] wide, every button in a row
 *  sharing that row's width equally so column edges line up from row to row -- the alignment
 *  the fixed-column design always had, just with a column count that adapts to text size. */
@Composable
private fun <T> SelectableButtonGrid(
    items: Collection<T>,
    columns: Int,
    label: (T) -> String,
    isSelected: (T) -> Boolean,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { rowItems ->
            // height(IntrinsicSize.Max) + fillMaxHeight() on each button keeps every button in
            // the row the same height as its tallest neighbor -- without it, a short one-line
            // label ("Large") sits shorter than a row-mate whose label wraps to two lines at a
            // larger text size ("Extra large"), the same unevenness reported and fixed for the
            // filter-condition segmented rows in FiltersScreen.kt.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rowItems.forEach { option ->
                    SelectableButton(
                        label = label(option),
                        selected = isSelected(option),
                        onClick = { onSelect(option) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
                // Pads out a short final row with invisible spacers so its buttons stay the same
                // width as every full row above, instead of stretching to fill the leftover
                // space -- the exact "wide crooked orphan" look in the reported screenshot.
                repeat(columns - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SelectableButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun DebugCard(vm: MainViewModel) {
    var countText by rememberSaveable { mutableStateOf("500") }
    var fake by remember { mutableStateOf(vm.fakeTransport) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Developer safety tests", fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Fake successful Gmail transport", modifier = Modifier.weight(1f))
                Switch(checked = fake, onCheckedChange = {
                    fake = it
                    vm.setFakeTransport(it)
                })
            }
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter(Char::isDigit).take(4) },
                label = { Text("Synthetic live events") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.injectSynthetic(countText.toIntOrNull() ?: 500) }) { Text("Inject") }
                OutlinedButton(onClick = vm::failNextFive) { Text("Fail next 5") }
            }
            Text("Use a debug build only. These controls feed the production queue and hard limiters.")
        }
    }
}
