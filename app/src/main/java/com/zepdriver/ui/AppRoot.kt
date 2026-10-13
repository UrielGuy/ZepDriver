package com.zepdriver.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zepdriver.DriveUiState
import com.zepdriver.PlayaBundle
import com.zepdriver.ui.drive.DriveScreen
import com.zepdriver.ui.favorites.FavoritesScreen
import com.zepdriver.ui.fleet.FleetScreen
import com.zepdriver.ui.route.RouteScreen
import com.zepdriver.ui.search.SearchScreen
import com.zepdriver.ui.settings.SettingsScreen
import com.zepdriver.ui.theme.ZepTheme
import com.zepdriver.ui.theme.rememberNightMode

@Composable
fun ZepRoot(vm: MainViewModel) {
    val bundle by vm.bundle.collectAsStateWithLifecycle()
    val ui by vm.drive.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()

    val night = rememberNightMode(prefs.themeMode, ui.drive?.isNight ?: isSystemInDarkTheme())
    SystemBarsEffect(night)
    KeepScreenOnEffect(ui.serviceRunning)

    ZepTheme(night) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            LocationGate(onGranted = vm::startDriving) {
                val b = bundle
                if (b == null) LoadingScreen() else MainScaffold(vm, b, ui)
            }
        }
    }
}

@Composable
private fun MainScaffold(vm: MainViewModel, bundle: PlayaBundle, ui: DriveUiState) {
    val tab by vm.tab.collectAsStateWithLifecycle()
    val locked = ui.isLocked()
    val shown = if (locked) Tab.DRIVE else tab
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.message) {
        val msg = ui.message
        if (msg != null) snackbar.showSnackbar(msg)
    }
    LaunchedEffect(vm) {
        vm.toasts.collect { snackbar.showSnackbar(it) }
    }

    // First run: offer to install an offline voice for directions (warnings are recorded).
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    if (!locked && !prefs.voicePromptSeen && voice.offlineVoice == false) {
        AlertDialog(
            onDismissRequest = vm::markVoicePromptSeen,
            title = { Text("Install an offline voice") },
            text = {
                Text(
                    "Warnings are pre-recorded and always speak. Turn-by-turn directions use your " +
                        "phone's text-to-speech, and there's no internet on the playa, so download an " +
                        "offline English voice now. On the next screen pick English (US) and download it.",
                )
            },
            confirmButton = { TextButton(onClick = vm::installVoice) { Text("Install voice") } },
            dismissButton = { TextButton(onClick = vm::markVoicePromptSeen) { Text("Not now") } },
        )
    }

    // Moving: only the driving view, and that's where we are when the car stops.
    LaunchedEffect(locked) {
        if (locked) vm.setTab(Tab.DRIVE)
    }
    // Back never leads into the other screens while moving.
    BackHandler(enabled = locked) { }
    BackHandler(enabled = !locked && tab != Tab.DRIVE) { vm.setTab(Tab.DRIVE) }

    Scaffold(
        bottomBar = { if (!locked) ZepNavigationBar(shown, vm::setTab) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            // The map stays alive under the other screens so it never reloads.
            DriveScreen(vm, bundle, ui, locked, contentPadding = padding)
            if (shown != Tab.DRIVE) {
                val density = LocalDensity.current
                val direction = LocalLayoutDirection.current
                val imeBottom = with(density) { WindowInsets.ime.getBottom(density).toDp() }
                val bottom = maxOf(padding.calculateBottomPadding(), imeBottom)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(
                        Modifier.padding(
                            start = padding.calculateStartPadding(direction),
                            top = padding.calculateTopPadding(),
                            end = padding.calculateEndPadding(direction),
                            bottom = bottom,
                        ),
                    ) {
                        when (shown) {
                            Tab.SEARCH -> SearchScreen(vm)
                            Tab.ROUTE -> RouteScreen(vm, ui)
                            Tab.FAVORITES -> FavoritesScreen(vm, ui)
                            Tab.FLEET -> FleetScreen(vm, bundle)
                            Tab.SETTINGS -> SettingsScreen(vm, bundle, ui)
                            Tab.DRIVE -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZepNavigationBar(current: Tab, onSelect: (Tab) -> Unit) {
    NavigationBar {
        for (t in Tab.entries) {
            NavigationBarItem(
                selected = t == current,
                onClick = { onSelect(t) },
                icon = { Icon(t.icon(), contentDescription = null) },
                label = { Text(t.label, maxLines = 1) },
                alwaysShowLabel = true,
            )
        }
    }
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.DRIVE -> Icons.Filled.Home
    Tab.SEARCH -> Icons.Filled.Search
    Tab.ROUTE -> Icons.AutoMirrored.Filled.List
    Tab.FAVORITES -> Icons.Filled.Star
    Tab.FLEET -> Icons.Filled.Person
    Tab.SETTINGS -> Icons.Filled.Settings
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Text(
            "Loading Black Rock City…",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

// --- Location permission -----------------------------------------------------------------

private fun Context.hasFineLocation(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

private fun startupPermissions(): Array<String> {
    val list = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) list += Manifest.permission.POST_NOTIFICATIONS
    return list.toTypedArray()
}

/**
 * Asks for location (and notifications) on first launch and starts the drive
 * service once location is granted. Without it, explains why and offers to
 * retry, open settings, or continue for planning only.
 */
@Composable
private fun LocationGate(onGranted: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasFineLocation()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var skipped by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = context.hasFineLocation()
        asked = true
    }

    LaunchedEffect(Unit) {
        if (!granted && !asked) launcher.launch(startupPermissions())
    }
    LaunchedEffect(granted) {
        if (granted) onGranted()
    }
    // The user may grant it from system settings and come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = context.hasFineLocation()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (granted || skipped || !asked) {
        content()
    } else {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("ZepDriver needs your location", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "The speedometer, zone warnings, your BRC address and navigation all run on GPS. " +
                    "Location stays on this phone; only your car's position is shared with other ZepDriver cars over the radio.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Button(onClick = { launcher.launch(startupPermissions()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Allow location")
            }
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open app settings") }
            TextButton(onClick = { skipped = true }) { Text("Continue without GPS (planning only)") }
        }
    }
}

// --- Window effects -------------------------------------------------------------------------

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Keeps the screen on while the drive service runs. */
@Composable
private fun KeepScreenOnEffect(keepOn: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, keepOn) {
        val window = activity?.window
        if (keepOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { }
    }
}

/** Light or dark status/navigation bar icons to match the theme. */
@Composable
private fun SystemBarsEffect(night: Boolean) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity
    LaunchedEffect(activity, night) {
        val style = if (night) {
            SystemBarStyle.dark(Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        }
        activity?.enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }
}
