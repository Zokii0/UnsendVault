# UnsendVault R8 rules. Most libraries ship consumer rules; keep this file minimal.

# kotlinx.serialization (backup file format).
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class com.riyadm.unsendvault.** {
    *** Companion;
}
-keepclasseswithmembers class com.riyadm.unsendvault.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.riyadm.unsendvault.**$$serializer { *; }

# SQLCipher uses JNI callbacks into these classes.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**

# Readable stack traces without shipping source file names.
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile
