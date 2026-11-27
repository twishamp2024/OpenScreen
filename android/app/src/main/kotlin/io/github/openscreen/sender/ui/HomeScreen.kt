package io.github.openscreen.sender.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.openscreen.core.capture.QualityPreset
import io.github.openscreen.core.net.Subnets
import io.github.openscreen.core.protocol.ProtocolConstants
import io.github.openscreen.core.protocol.ReceiverInfo
import io.github.openscreen.core.protocol.VideoInfo
import io.github.openscreen.core.session.SessionState
import io.github.openscreen.core.session.isStreaming
import io.github.openscreen.sender.R
import io.github.openscreen.sender.ui.theme.OpenScreenTheme
import kotlinx.coroutines.delay
import java.util.Locale

data class HomeActions(
    val scan: () -> Unit = {},
    val paste: () -> Unit = {},
    val startStreaming: () -> Unit = {},
    val stopStreaming: () -> Unit = {},
    val disconnect: () -> Unit = {},
    val dismissError: () -> Unit = {},
    val setPreset: (QualityPreset) -> Unit = {},
    val setFps: (Int) -> Unit = {},
)

@Composable
fun HomeScreen(state: UiState, appVersion: String, actions: HomeActions, modifier: Modifier = Modifier) {
    Scaffold(modifier = modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header()
            StatusCard(state, actions)
            QualityCard(state, actions)
            DiagnosticsCard(state)
            Text(
                stringResource(R.string.view_only_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.version_line, appVersion, ProtocolConstants.PROTOCOL_VERSION),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Header() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(40.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("▣", color = MaterialTheme.colorScheme.onPrimary, fontSize = 20.sp)
        }
        Column {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.tagline_short),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private enum class Tone { Neutral, Busy, Good, Bad }

@Composable
private fun StatusCard(state: UiState, actions: HomeActions) {
    val s = state.session
    val tone = when (s) {
        SessionState.Idle -> Tone.Neutral
        is SessionState.Connecting, is SessionState.Negotiating, is SessionState.Reconnecting -> Tone.Busy
        is SessionState.Connected -> Tone.Good
        is SessionState.Failed -> Tone.Bad
    }
    val dot by animateColorAsState(
        when (tone) {
            Tone.Neutral -> MaterialTheme.colorScheme.outline
            Tone.Busy -> MaterialTheme.colorScheme.tertiary
            Tone.Good -> Color(0xFF1A9E55)
            Tone.Bad -> MaterialTheme.colorScheme.error
        },
        label = "status",
    )
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(10.dp))
                Text(statusTitle(s), style = MaterialTheme.typography.titleLarge)
            }
            statusBody(s, state)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            receiverOf(s)?.let { ReceiverLine(it, state.receiverAddress) }
            if (s.isStreaming) StreamingTimer(state.streamingSince)

            when (s) {
                SessionState.Idle -> {
                    Button(onClick = actions.scan, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_scan_qr)) }
                    TextButton(onClick = actions.paste, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_paste_link)) }
                }
                is SessionState.Connecting, is SessionState.Negotiating -> {
                    OutlinedButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
                }
                is SessionState.Connected -> if (s.video == null) {
                    Button(onClick = actions.startStreaming, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_start_streaming)) }
                    OutlinedButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_disconnect)) }
                } else {
                    Button(
                        onClick = actions.stopStreaming,
                        modifier = Modifier.fillMaxWidth(),
                        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError,
                        ),
                    ) { Text(stringResource(R.string.action_stop_streaming)) }
                    OutlinedButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_disconnect)) }
                }
                is SessionState.Reconnecting -> {
                    OutlinedButton(onClick = actions.disconnect, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_disconnect)) }
                }
                is SessionState.Failed -> {
                    Button(onClick = { actions.dismissError(); actions.scan() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.action_scan_again))
                    }
                    TextButton(onClick = actions.dismissError, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_dismiss)) }
                }
            }
        }
    }
}

@Composable
private fun statusTitle(s: SessionState): String = when (s) {
    SessionState.Idle -> stringResource(R.string.status_not_paired)
    is SessionState.Connecting -> stringResource(R.string.status_connecting)
    is SessionState.Negotiating -> stringResource(R.string.status_negotiating)
    is SessionState.Connected -> stringResource(if (s.video != null) R.string.status_streaming else R.string.status_ready)
    is SessionState.Reconnecting -> stringResource(R.string.status_reconnecting)
    is SessionState.Failed -> stringResource(s.error.message().title)
}

@Composable
private fun statusBody(s: SessionState, state: UiState): String? = when (s) {
    SessionState.Idle -> stringResource(if (state.wifi.connected) R.string.hint_idle else R.string.hint_no_wifi)
    is SessionState.Connecting -> s.receiverName?.let { stringResource(R.string.hint_connecting_to, it) }
    is SessionState.Negotiating -> stringResource(R.string.hint_negotiating)
    is SessionState.Connected -> if (s.video == null) stringResource(R.string.hint_ready) else null
    is SessionState.Reconnecting -> stringResource(R.string.hint_reconnecting, s.attempt)
    is SessionState.Failed -> stringResource(s.error.message().body)
}

private fun receiverOf(s: SessionState): ReceiverInfo? = when (s) {
    is SessionState.Negotiating -> s.receiver
    is SessionState.Connected -> s.receiver
    is SessionState.Reconnecting -> s.receiver
    else -> null
}

@Composable
private fun ReceiverLine(receiver: ReceiverInfo, address: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(receiver.name, style = MaterialTheme.typography.titleSmall)
            Text(
                listOfNotNull(stringResource(R.string.receiver_windows), address).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("v${receiver.appVersion}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StreamingTimer(since: Long?) {
    var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
    LaunchedEffect(since) {
        while (since != null) {
            now = android.os.SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    val seconds = if (since == null) 0 else ((now - since) / 1000).coerceAtLeast(0)
    Text(
        formatDuration(seconds),
        style = MaterialTheme.typography.displaySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

fun formatDuration(totalSeconds: Long): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%02d:%02d", m, s)
}

@Composable
private fun QualityCard(state: UiState, actions: HomeActions) {
    val locked = state.session.isStreaming
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.quality_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.quality_resolution), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                QualityPreset.entries.forEachIndexed { i, p ->
                    SegmentedButton(
                        selected = state.settings.preset == p,
                        onClick = { actions.setPreset(p) },
                        enabled = !locked,
                        shape = SegmentedButtonDefaults.itemShape(i, QualityPreset.entries.size),
                    ) { Text(if (p == QualityPreset.P720) "720p" else "1080p") }
                }
            }
            Text(stringResource(R.string.quality_fps), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(30, 60).forEachIndexed { i, fps ->
                    SegmentedButton(
                        selected = state.settings.fps == fps || (fps == 30 && !state.supports60Fps),
                        onClick = { actions.setFps(fps) },
                        enabled = !locked && (fps == 30 || state.supports60Fps),
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text("$fps FPS") }
                }
            }
            val hint = when {
                locked -> R.string.quality_locked
                !state.supports60Fps -> R.string.quality_no_60
                else -> R.string.quality_hint
            }
            Text(stringResource(hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DiagnosticsCard(state: UiState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.clickable { expanded = !expanded }.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (expanded) {
                val w = state.wifi
                DiagRow(stringResource(R.string.diag_wifi), if (w.connected) stringResource(R.string.diag_yes) else stringResource(R.string.diag_no))
                DiagRow(stringResource(R.string.diag_phone_ip), w.ipv4?.let { "$it/${w.prefixLength}" } ?: "—")
                DiagRow(stringResource(R.string.diag_internet), if (w.hasInternet) stringResource(R.string.diag_yes) else stringResource(R.string.diag_no_lan_only))
                val pc = state.receiverAddress
                DiagRow(stringResource(R.string.diag_pc_ip), pc ?: "—")
                if (pc != null && w.ipv4 != null && w.prefixLength != null) {
                    DiagRow(
                        stringResource(R.string.diag_same_subnet),
                        if (Subnets.sameSubnet(w.ipv4!!, w.prefixLength!!, pc)) stringResource(R.string.diag_yes) else stringResource(R.string.diag_no),
                    )
                }
                val st = state.stats
                if (st != null) {
                    DiagRow(stringResource(R.string.diag_fps), st.framesPerSecond?.let { String.format(Locale.ROOT, "%.0f", it) } ?: "—")
                    DiagRow(stringResource(R.string.diag_bitrate), st.bitrateBps?.let { formatBitrate(it) } ?: "—")
                    DiagRow(stringResource(R.string.diag_resolution), if (st.frameWidth != null) "${st.frameWidth}×${st.frameHeight}" else "—")
                    DiagRow(stringResource(R.string.diag_rtt), st.roundTripMs?.let { String.format(Locale.ROOT, "%.0f ms", it) } ?: "—")
                    DiagRow(stringResource(R.string.diag_available), st.availableBitrateBps?.let { formatBitrate(it) } ?: "—")
                    DiagRow(stringResource(R.string.diag_encoder), st.encoder ?: "—")
                    DiagRow(stringResource(R.string.diag_limit), st.qualityLimitation ?: "—")
                }
            }
        }
    }
}

fun formatBitrate(bps: Long): String =
    if (bps >= 1_000_000) String.format(Locale.ROOT, "%.1f Mbps", bps / 1e6) else String.format(Locale.ROOT, "%d kbps", bps / 1000)

@Composable
private fun DiagRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
    Spacer(Modifier.height(2.dp))
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun HomePreviewStreaming() {
    OpenScreenTheme(dynamicColor = false) {
        HomeScreen(
            state = UiState(
                session = SessionState.Connected(ReceiverInfo("Study PC", "windows", "0.1.0"), VideoInfo(864, 1920, 30, "H264", 0)),
                streamingSince = android.os.SystemClock.elapsedRealtime() - 75_000,
                receiverAddress = "192.168.1.20",
            ),
            appVersion = "0.1.0",
            actions = HomeActions(),
        )
    }
}
