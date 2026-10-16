# The web page calls these methods by name through window.AndroidCodex.
-keepclassmembers class io.github.jayswansocool48.gamecodex.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
