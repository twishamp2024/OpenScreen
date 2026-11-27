package io.github.openscreen.sender.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.openscreen.core.capture.QualityPreset
import io.github.openscreen.core.net.Subnets
import io.github.openscreen.core.net.WifiStatus
import io.github.openscreen.core.protocol.PairingInfo
import io.github.openscreen.core.protocol.PairingUri
import io.github.openscreen.core.protocol.PairingUriError
import io.github.openscreen.core.protocol.PairingUriResult
import io.github.openscreen.core.session.SessionState
import io.github.openscreen.core.webrtc.SenderStats
import io.github.openscreen.sender.OpenScreenApp
import io.github.openscreen.sender.settings.StreamSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class UiState(
    val session: SessionState = SessionState.Idle,
    val wifi: WifiStatus = WifiStatus(connected = false),
    val settings: StreamSettings = StreamSettings(),
    val supports60Fps: Boolean = false,
    val stats: SenderStats? = null,
    val streamingSince: Long? = null,
    val receiverAddress: String? = null,
)

/** A scanned code waiting for the user to confirm, with an optional network warning. */
data class PendingPairing(val info: PairingInfo, val differentNetwork: Boolean)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = (app as OpenScreenApp).controller

    val state: StateFlow<UiState> = combine(
        controller.state,
        controller.wifi.status,
        controller.settings.settings,
        controller.stats,
        controller.streamingSince,
    ) { session, wifi, settings, stats, since ->
        UiState(session, wifi, settings, controller.supports60Fps, stats, since, controller.receiverAddress)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private val _pending = MutableStateFlow<PendingPairing?>(null)
    val pending: StateFlow<PendingPairing?> = _pending.asStateFlow()

    private val _qrError = MutableStateFlow<PairingUriError?>(null)
    val qrError: StateFlow<PairingUriError?> = _qrError.asStateFlow()

    /** Parses a scanned or pasted pairing link. Returns true if it was valid. */
    fun onCode(text: String, requireConfirmation: Boolean): Boolean =
        when (val r = PairingUri.parse(text.trim())) {
            is PairingUriResult.Invalid -> {
                _qrError.value = r.error
                false
            }
            is PairingUriResult.Valid -> {
                _qrError.value = null
                val wifi = controller.wifi.status.value
                val different = wifi.ipv4 != null &&
                    Subnets.firstReachable(r.info.hosts, wifi.ipv4, wifi.prefixLength) == null
                if (requireConfirmation || different) {
                    _pending.value = PendingPairing(r.info, different)
                } else {
                    controller.pair(r.info)
                }
                true
            }
        }

    fun confirmPending() {
        _pending.value?.let { controller.pair(it.info) }
        _pending.value = null
    }

    fun cancelPending() {
        _pending.value = null
    }

    fun clearQrError() {
        _qrError.value = null
    }

    fun disconnect() = controller.disconnect()
    fun dismissError() = controller.dismissError()
    fun setPreset(p: QualityPreset) = controller.settings.update { it.copy(preset = p) }
    fun setFps(fps: Int) = controller.settings.update { it.copy(fps = fps) }
}
