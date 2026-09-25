package ai.techtroy.clockcanvas.ui

import ai.techtroy.clockcanvas.ClockDesign
import ai.techtroy.clockcanvas.data.DesignStore

/**
 * The editor's single mutable design plus its dirty state.
 *
 * The editor UI is rebuilt from this object rather than two-way bound, because
 * every control here (chips, sliders, colour dots) is a bespoke view and because
 * a rebuild is what guarantees the preview, the widget-preview buckets and the
 * saved file can never disagree with the control you just touched.
 */
class DesignBinding(initial: ClockDesign, private val store: DesignStore) {

    var design: ClockDesign = initial
        private set
    var dirty: Boolean = false
        private set

    fun update(transform: (ClockDesign.Builder) -> Unit) {
        design = design.copyWith(transform)
        dirty = true
        onChange?.invoke(false)
    }

    /** Same as [update] but forces the host screen to rebuild its whole form. */
    fun structural(transform: (ClockDesign.Builder) -> Unit) {
        design = design.copyWith(transform)
        dirty = true
        onChange?.invoke(true)
    }

    fun markSaved() {
        dirty = false
    }

    fun save(): ClockDesign {
        val saved = store.upsert(design)
        design = saved
        dirty = false
        return saved
    }

    var onChange: ((rebuild: Boolean) -> Unit)? = null
}
