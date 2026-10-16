# llama_engine.cpp calls onBytes(byte[]) on the callback by name.
-keep interface io.github.jayswansocool48.codeling.LlamaEngine$TokenCallback { *; }
-keep class * implements io.github.jayswansocool48.codeling.LlamaEngine$TokenCallback { void onBytes(byte[]); }
-keepclasseswithmembernames class io.github.jayswansocool48.codeling.LlamaEngine { native <methods>; }
-keepclassmembers class io.github.jayswansocool48.codeling.AiBridge { @android.webkit.JavascriptInterface <methods>; }
