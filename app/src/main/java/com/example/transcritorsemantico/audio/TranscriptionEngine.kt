package com.example.transcritorsemantico.audio

enum class TranscriptionEngine(
    val id: String,
    val label: String,
    val description: String,
    val preferOffline: Boolean,
    val transcriptionEnabled: Boolean,
) {
    ANDROID_SYSTEM(
        id = "android_system",
        label = "Android Hibrido",
        description = "Usa o reconhecedor do Android com fallback online quando necessario.",
        preferOffline = false,
        transcriptionEnabled = true,
    ),
    ANDROID_OFFLINE(
        id = "android_offline",
        label = "Android Offline",
        description = "Prioriza pacote de idioma local. Falha se o aparelho nao tiver suporte.",
        preferOffline = true,
        transcriptionEnabled = true,
    ),
    AUDIO_ONLY(
        id = "audio_only",
        label = "So Gravar",
        description = "Salva o audio sem transcrever. Nao entra na busca semantica.",
        preferOffline = false,
        transcriptionEnabled = false,
    );

    companion object {
        fun fromId(id: String): TranscriptionEngine {
            return entries.firstOrNull { it.id == id } ?: ANDROID_SYSTEM
        }
    }
}
