package com.orktts.app

import kotlinx.coroutines.flow.MutableStateFlow

data class ReaderUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val book: Book? = null,
    val chapterIndex: Int = 0,
    val sentenceIndex: Int = 0,
    val isPlaying: Boolean = false
)

/** Simple shared state so MainActivity can observe what ReaderService is doing. */
object ReaderState {
    val state = MutableStateFlow(ReaderUiState())
}
