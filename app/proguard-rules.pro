# PiSandbox R8 rules (release build).

# Termux terminal-emulator / terminal-view: JNI (libtermux.so calls into these classes by name) -> keep all.
-keep class com.termux.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Readable stack traces on the "Copy details" crash screen (no obfuscated line numbers lost).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# commons-compress / commons-io / commons-lang3 reference optional codecs and APIs we never use.
-dontwarn org.brotli.dec.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.annotation.**
-dontwarn java.lang.management.**
-dontwarn org.apache.commons.compress.harmony.**
