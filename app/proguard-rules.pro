# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
