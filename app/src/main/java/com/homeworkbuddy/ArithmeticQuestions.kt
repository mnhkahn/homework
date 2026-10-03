package com.homeworkbuddy

/** One expression per line (or semicolon), preserving operators and answer blanks. */
fun arithmeticQuestions(content: String?): List<String> = content.orEmpty()
    .split(Regex("[\\r\\n;；]+"))
    .map { it.trim().replace(Regex("^(?:[-*]\\s+|\\d+(?:\\.\\s+|[)、]\\s*))"), "").trim() }
    .filter(String::isNotBlank)

data class ArithmeticCellSize(val fontSp: Int, val width: Int, val height: Int)
data class ArithmeticGridPlan(val columns: Int, val fontSp: Int, val fits: Boolean)

/** Prefer the largest readable type that fits every question in the available rectangle. */
fun arithmeticGridPlan(count: Int, width: Int, height: Int, gap: Int, sizes: List<ArithmeticCellSize>): ArithmeticGridPlan {
    require(sizes.isNotEmpty())
    val candidates = sizes.sortedByDescending { it.fontSp }
    fun plan(size: ArithmeticCellSize): ArithmeticGridPlan {
        val columns = ((width.coerceAtLeast(0) + gap) / (size.width.coerceAtLeast(1) + gap))
            .coerceIn(1, count.coerceAtLeast(1))
        val rows = (count + columns - 1) / columns
        val fits = size.width <= width && rows * size.height + (rows - 1).coerceAtLeast(0) * gap <= height
        return ArithmeticGridPlan(columns, size.fontSp, fits)
    }
    return candidates.map(::plan).firstOrNull { it.fits } ?: plan(candidates.last())
}
