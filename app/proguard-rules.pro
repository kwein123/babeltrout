# Babeltrout release shrinking rules.
#
# ML Kit (translate, language-id) and AndroidX ship consumer keep rules inside their AARs, and the
# app itself uses no reflection, so no extra keeps are needed. If a release build crashes where the
# debug build doesn't, add the class named in the stack trace here with -keep and file an issue.

# Keep line numbers so release crash reports are readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
