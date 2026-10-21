package com.zepdriver.ui

import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import com.zepdriver.AppGraph
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels

/**
 * The only activity. Hosts the Compose UI; the drive service, mesh and data
 * live in the platform layer and are reached through AppGraph.
 */
class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent { ZepRoot(vm) }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's voice download: look again.
        if (AppGraph.voice.state.value.offlineVoice == false) AppGraph.voice.refresh()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) vm.onUsbAttached()
        ScreenshotScenes.handle(intent, vm)
    }
}
