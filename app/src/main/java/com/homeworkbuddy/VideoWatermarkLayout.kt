package com.homeworkbuddy

/** Keep text, spacing and margins proportional, with 640×480 as the reference frame. */
internal fun videoWatermarkScale(width: Int, height: Int): Float =
    minOf(width / 640f, height / 480f) * .55f

/** Upscale the scene before drawing text, so low-bandwidth capture doesn't rasterize Chinese at 4px. */
internal fun readableStreamFrameSize(width: Int, height: Int): Pair<Int, Int> {
    require(width > 0 && height > 0)
    val scale = maxOf(1f, 640f / width, 480f / height)
    return kotlin.math.ceil(width * scale.toDouble()).toInt() to
        kotlin.math.ceil(height * scale.toDouble()).toInt()
}
