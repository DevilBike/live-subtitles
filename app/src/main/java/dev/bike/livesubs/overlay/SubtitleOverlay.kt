package dev.bike.livesubs.overlay

import dev.bike.livesubs.asr.SubtitleUpdate

/** Step 4 implements this as a draggable TYPE_APPLICATION_OVERLAY window. */
interface SubtitleOverlay {
    fun show()
    fun render(update: SubtitleUpdate)
    fun hide()
}
