package com.homeworkbuddy

/** Keep text, spacing and margins proportional, with 640×480 as the reference frame. */
internal fun videoWatermarkScale(width: Int, height: Int): Float =
    minOf(width / 640f, height / 480f) * .55f
