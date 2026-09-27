// Top-level build file for ClockCanvas.
//
// The module itself is dependency-light on purpose: the widget renderer is
// pure Canvas/Bitmap and the editor UI is framework Views, so the whole app
// can be compiled (and was originally verified) without Compose, Room or
// Media3 available. See README.md, "Building it".
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
