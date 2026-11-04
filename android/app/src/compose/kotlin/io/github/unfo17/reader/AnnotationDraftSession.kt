package io.github.unfo17.reader

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/** Retains an uncommitted annotation while switching between its form and source selection. */
internal class AnnotationDraftSession : ViewModel() {
    var mark by mutableStateOf<ReadingMark?>(null)
    var selectingRange by mutableStateOf(false)
    var expectedRevision: Long? = null

    fun open(mark: ReadingMark, expected: Long?) {
        this.mark = mark
        expectedRevision = expected
        selectingRange = false
    }

    fun clear() { mark = null; expectedRevision = null; selectingRange = false }
}
