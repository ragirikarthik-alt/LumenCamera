# LiteRT-LM: JNI bridge and Gson-based message conversion.
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**
# kotlinx.serialization DTOs used for model output parsing and persisted JSON.
-keepclassmembers @kotlinx.serialization.Serializable class com.aicamera.photo.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.aicamera.photo.**$$serializer { *; }
# WorkManager workers are instantiated reflectively.
-keep class com.aicamera.photo.services.*Worker { <init>(...); }
