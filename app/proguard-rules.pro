# youtubedl-android, ffmpeg, aria2c JNI bindings
-keep class com.yausername.** { *; }
-dontwarn com.yausername.**

# kotlinx.serialization runtime & generated serializers
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn kotlinx.serialization.**
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    <fields>;
    <methods>;
}
-keep class kotlinx.serialization.** { *; }

# App models & their generated $serializer classes
-keep class com.anonrode.downloader.data.models.** { *; }
-keepclassmembers class com.anonrode.downloader.data.models.** {
    *** Companion;
    *** $serializer;
    *** serializer(...);
    <fields>;
    <methods>;
}

# Network and image loading
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class coil.** { *; }
-dontwarn coil.**
