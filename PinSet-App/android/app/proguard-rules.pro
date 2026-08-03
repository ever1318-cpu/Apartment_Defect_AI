# Keep Room-generated classes
-keep class androidx.room.** { *; }
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static ** create*(...);
}

# Keep OpenCV
-keep class org.opencv.** { *; }
-dontwarn org.opencv.**

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.axlife.pinset.**$$serializer { *; }
-keepclassmembers class com.axlife.pinset.** {
    *** Companion;
}
-keepclasseswithmembers class com.axlife.pinset.** {
    kotlinx.serialization.KSerializer serializer(...);
}
