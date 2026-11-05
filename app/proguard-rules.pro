# JNI entry points are looked up by name.
-keepclasseswithmembernames class * { native <methods>; }
-keep class dev.compy.term.GhosttyTerminal { *; }
