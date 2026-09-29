# Babeltrout release shrinking rules.
#
# ML Kit (translate, language-id) and AndroidX ship consumer keep rules inside their AARs, and the
# app itself uses no reflection, so no extra keeps are needed. If a release build crashes where the
# debug build doesn't, add the class named in the stack trace here with -keep and file an issue.

# Keep line numbers so release crash reports are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# sherpa-onnx: native code reads Kotlin config objects and calls back into Kotlin by name through JNI,
# and its AAR ships no keep rules. Without these, R8 renames the fields and built-in voices fail in
# release builds only.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# The audio callback handed to OfflineTts.generateWithCallback is looked up from native code by the
# exact signature invoke([F)Ljava/lang/Integer;. Without this rule R8 leaves only invoke(Object) and
# built-in voices crash (NoSuchMethodError) in release builds only.
-keep class com.kevin.babeltrout.PiperSpeaker$StreamingSink {
    java.lang.Integer invoke(float[]);
}

# commons-compress references optional codecs (xz, zstd, brotli, ...) that Babeltrout doesn't include;
# only bzip2 + tar are used.
-dontwarn org.apache.commons.compress.**
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
