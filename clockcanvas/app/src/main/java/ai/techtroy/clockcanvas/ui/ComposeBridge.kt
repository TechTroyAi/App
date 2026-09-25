package ai.techtroy.clockcanvas.ui

/**
 * indirection between the (always built) Views UI and the (optional) Compose
 * preview screen. The offline `kotlinc` build in this repo compiles the app
 * without the Compose plugin, so the Compose activity is referenced by class
 * name only - see EditorActivity.canOpenComposePreview().
 */
object ComposeBridge {
    const val PREVIEW_CLASS = "ai.techtroy.clockcanvas.ui.compose.PreviewActivity"
    const val EXTRA_DESIGN_ID = "design_id"
}
