# Add project specific ProGuard rules here.
-keep public class com.masjidazanclock.app.** { *; }
-keep public class * extends com.getcapacitor.Plugin
-keepclassmembers class * extends com.getcapacitor.Plugin { public *; }
-keepattributes *Annotation*
