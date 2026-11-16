-keep class org.webrtc.** { *; }
-keep class app.onklav.home.NeuralSpeechNative { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod

# JNI_OnLoad resolves these helpers by name; org.webrtc alone is insufficient.
-keep class org.jni_zero.** { *; }
