# App-specific R8 rules. Library rules ship with :core (consumer-rules.pro).

# libwebrtc's native code calls into these Java classes by name over JNI;
# renaming or stripping them would crash release builds at runtime.
-keep class org.webrtc.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
-dontwarn org.webrtc.**
