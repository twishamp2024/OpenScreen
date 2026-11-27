# Keep kotlinx.serialization serializers for protocol models.
-keepclassmembers class io.github.openscreen.core.protocol.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
