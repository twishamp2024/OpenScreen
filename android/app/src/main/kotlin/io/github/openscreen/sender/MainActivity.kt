package io.github.openscreen.sender

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.openscreen.sender.service.StreamingService
import io.github.openscreen.sender.ui.HomeActions
import io.github.openscreen.sender.ui.HomeScreen
import io.github.openscreen.sender.ui.MainViewModel
import io.github.openscreen.sender.ui.message
import io.github.openscreen.sender.ui.scanner.ScannerScreen
import io.github.openscreen.sender.ui.theme.OpenScreenTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private var showScanner by mutableStateOf(false)
    private var showPaste by mutableStateOf(false)
    private var notice by mutableStateOf<Int?>(null)

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            StreamingService.start(this, result.resultCode, data)
        } else {
            notice = R.string.err_capture_denied
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) showScanner = true else notice = R.string.err_camera_denied
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Streaming works either way; without it the persistent notification is hidden.
        requestProjection()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleLink(intent)
        setContent {
            OpenScreenTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                val pending by vm.pending.collectAsStateWithLifecycle()
                val qrError by vm.qrError.collectAsStateWithLifecycle()

                if (showScanner) {
                    BackHandler { showScanner = false }
                    ScannerScreen(
                        errorText = qrError?.let { stringResource(it.message()) },
                        onCode = { text -> if (vm.onCode(text, requireConfirmation = false)) showScanner = false },
                        onPaste = { showPaste = true },
                        onClose = { showScanner = false; vm.clearQrError() },
                    )
                } else {
                    HomeScreen(
                        state = state,
                        appVersion = BuildConfig.VERSION_NAME,
                        actions = HomeActions(
                            scan = ::openScanner,
                            paste = { showPaste = true },
                            startStreaming = ::startStreaming,
                            stopStreaming = { StreamingService.stop(this) },
                            disconnect = vm::disconnect,
                            dismissError = vm::dismissError,
                            setPreset = vm::setPreset,
                            setFps = vm::setFps,
                        ),
                    )
                }

                if (showPaste) PasteDialog(
                    error = qrError?.let { stringResource(it.message()) },
                    onSubmit = { if (vm.onCode(it, requireConfirmation = false)) { showPaste = false; showScanner = false } },
                    onDismiss = { showPaste = false; vm.clearQrError() },
                )

                pending?.let { p ->
                    AlertDialog(
                        onDismissRequest = vm::cancelPending,
                        title = { Text(stringResource(R.string.confirm_title)) },
                        text = {
                            Text(
                                stringResource(R.string.confirm_body, p.info.name ?: p.info.hosts.first(), p.info.hosts.joinToString()) +
                                    if (p.differentNetwork) "\n\n" + stringResource(R.string.warn_different_network) else "",
                            )
                        },
                        confirmButton = { TextButton(onClick = vm::confirmPending) { Text(stringResource(R.string.action_connect)) } },
                        dismissButton = { TextButton(onClick = vm::cancelPending) { Text(stringResource(R.string.action_cancel)) } },
                    )
                }

                notice?.let { res ->
                    AlertDialog(
                        onDismissRequest = { notice = null },
                        text = { Text(stringResource(res)) },
                        confirmButton = { TextButton(onClick = { notice = null }) { Text(stringResource(R.string.action_ok)) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** `openscreen://pair?...` opened from the system camera: always ask before connecting. */
    private fun handleLink(intent: Intent?) {
        val uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        vm.onCode(uri, requireConfirmation = true)
        intent.data = null
    }

    private fun openScanner() {
        vm.clearQrError()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            showScanner = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startStreaming() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestProjection()
        }
    }

    private fun requestProjection() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Ask for the whole screen, not a single app (docs/ARCHITECTURE.md TD-11).
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionConsent.launch(intent)
    }
}

@Composable
private fun PasteDialog(error: String?, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paste_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.paste_hint)) },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onSubmit(text) }) { Text(stringResource(R.string.action_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
