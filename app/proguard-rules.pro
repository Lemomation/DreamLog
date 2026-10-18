# Room entities are accessed through generated code; keep their members.
-keep class com.lemomation.dreamlog.data.** { *; }

# Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }
-dontwarn kotlinx.coroutines.**

# AndroidX Room
-dontwarn androidx.room.paging.**
