# App-specific R8 keep rules (docs/11 §2.4). Library consumer rules (Tink, kotlinx.serialization,
# Ktor/OkHttp, Play services, Compose) ship inside their AARs/JARs and are applied automatically —
# do NOT duplicate them here. Add a rule ONLY with a comment naming the runtime failure it fixes.

# Tink's optional integrations (KMS clients, protobuf full) are absent by design; R8 -dontwarn
# silences the missing-class references from code paths we never execute.
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.joda.time.**
