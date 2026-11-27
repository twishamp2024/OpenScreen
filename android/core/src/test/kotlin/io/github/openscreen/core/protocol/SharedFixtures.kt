package io.github.openscreen.core.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

/** Loads files from `shared/protocol/` (path injected by Gradle, see core/build.gradle.kts). */
object SharedFixtures {
    private val dir: File by lazy {
        val path = System.getProperty("openscreen.protocolDir")
            ?: error("System property openscreen.protocolDir is not set; run the tests through Gradle.")
        File(path).also { require(it.isDirectory) { "Shared protocol directory not found: $it" } }
    }

    fun files(relativeDir: String): List<File> =
        File(dir, relativeDir).listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    fun json(relativePath: String): JsonObject =
        Json.parseToJsonElement(File(dir, relativePath).readText()).jsonObject
}
