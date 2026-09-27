# ClockCanvas keeps R8/shrinking OFF in release (see app/build.gradle.kts:
# isMinifyEnabled = false). The APK is 3.9 MB with 5 bundled fonts and no
# third-party runtime libraries, so shrinking buys little and would make the
# offline/Gradle parity this project depends on harder to reason about.
#
# The rules below are what a future enablement needs, and are kept here so they
# are not discovered the hard way:
#
# 1. The widget provider, the wallpaper service and LocalFileProvider are named
#    from AndroidManifest.xml, so AGP's aapt-derived keep rules already cover them.
#    The Compose preview activity is looked up by *name* at runtime:
#       -keep class ai.techtroy.clockcanvas.ui.compose.PreviewActivity { <init>(); }
# 2. DesignCodec reflects over the ClockDesign *field names* only in tests, not in
#    the shipped path (it reads/writes a hand-rolled JSON object), so no model keeps
#    are required. If that ever changes, keep the data classes:
#       -keepclassmembers class ai.techtroy.clockcanvas.ClockDesign { *; }
# 3. Enum names are the on-disk format (`style: "analog"`), so the values must not
#    be renamed/removed by shrinking:
#       -keepclassmembers enum ai.techtroy.clockcanvas.* { public static **[] values(); public static ** valueOf(java.lang.String); }
