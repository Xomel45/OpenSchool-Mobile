# JNA finds its native methods, structures and callbacks by reflection.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# The UniFFI bindings: JNA calls into them by name (callbacks, records, the library interface).
-keep class uniffi.** { *; }
# Line numbers in crash reports (the diagnostics screen shows the last crash).
-keepattributes SourceFile,LineNumberTable
