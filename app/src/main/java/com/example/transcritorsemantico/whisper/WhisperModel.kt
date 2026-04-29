package com.example.transcritorsemantico.whisper

enum class WhisperModel(
    val id: String,
    val title: String,
    val fileName: String,
    val remoteUrl: String,
    val sizeLabel: String,
) {
    BASE(
        id = "base",
        title = "base",
        fileName = "ggml-base-q5_1.bin",
        remoteUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin?download=true",
        sizeLabel = "~57 MB",
    ),
    SMALL(
        id = "small",
        title = "small",
        fileName = "ggml-small-q5_1.bin",
        remoteUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin?download=true",
        sizeLabel = "~182 MB",
    ),
    MEDIUM(
        id = "medium",
        title = "medium",
        fileName = "ggml-medium-q5_0.bin",
        remoteUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-medium-q5_0.bin?download=true",
        sizeLabel = "~514 MB",
    ),
    LARGE_V3_TURBO(
        id = "large-v3-turbo",
        title = "large-v3-turbo",
        fileName = "ggml-large-v3-turbo-q5_0.bin",
        remoteUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo-q5_0.bin?download=true",
        sizeLabel = "~547 MB",
    );

    companion object {
        fun fromId(id: String): WhisperModel {
            return entries.firstOrNull { it.id == id } ?: BASE
        }
    }
}
