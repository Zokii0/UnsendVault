# UnsendVault R8 rules. Most libraries ship consumer rules; keep this file minimal.

# kotlinx.serialization (backup file format and type-safe navigation routes).
# kotlinx-serialization-core ships R8 rules that already keep Companion/serializer()/INSTANCE of every @Serializable
# class. These rules are a narrow safety net limited to the two packages that declare @Serializable types:
# data.backup (encrypted backup DTOs) and ui.navigation (route objects).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class com.riyadm.unsendvault.data.backup.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers @kotlinx.serialization.Serializable class com.riyadm.unsendvault.ui.navigation.** {
    *** Companion;
    public static ** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.riyadm.unsendvault.data.backup.**,com.riyadm.unsendvault.ui.navigation.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.riyadm.unsendvault.data.backup.**$$serializer,com.riyadm.unsendvault.ui.navigation.**$$serializer { *; }

# SQLCipher uses JNI callbacks into these classes.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Release builds drop verbose/debug/info logging (app and libraries); warnings and errors stay.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Readable stack traces without shipping source file names.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
