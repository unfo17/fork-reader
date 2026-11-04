package io.github.unfo17.reader

/** Bounded locator/preview contexts must not leave half a UTF-16 surrogate pair. */
internal fun textSuffix(value: String, length: Int): String {
    var start = (value.length - length).coerceAtLeast(0)
    if (start > 0 && start < value.length && Character.isLowSurrogate(value[start]) && Character.isHighSurrogate(value[start - 1])) start++
    return value.substring(start)
}
internal fun textPrefix(value: String, length: Int): String {
    var end = minOf(length, value.length)
    if (end < value.length && end > 0 && Character.isHighSurrogate(value[end - 1]) && Character.isLowSurrogate(value[end])) end--
    return value.substring(0, end)
}
