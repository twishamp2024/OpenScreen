package io.github.openscreen.sender

import android.app.Application
import io.github.openscreen.sender.service.SenderController

class OpenScreenApp : Application() {
    /** Process-wide session owner; lives as long as the process (the service keeps it alive while streaming). */
    val controller: SenderController by lazy { SenderController(this) }
}
