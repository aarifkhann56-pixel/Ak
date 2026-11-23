package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class AiMode(
    val displayName: String,
    val shortLabel: String,
    val description: String,
    val systemPrompt: String,
) {
    CHAT(
        displayName = "General AI",
        shortLabel = "Chat",
        description = "Conversational assistant for writing, ideas, explanations, and research.",
        systemPrompt = "You are Nova AI, a sharp, helpful, and accurate conversational AI assistant. " +
            "Provide clear, structured responses with Markdown formatting when helpful, and include fenced code blocks with language tags for any code.",
    ),
    APK_CODER(
        displayName = "APK & Kotlin Studio",
        shortLabel = "APK Coder",
        description = "Android expert for Jetpack Compose, Kotlin, Gradle builds, and APK architecture.",
        systemPrompt = "You are Nova APK Studio, an expert senior Android & Kotlin engineer. " +
            "Help the user build working Android apps and APKs using Jetpack Compose, Material 3, Kotlin Coroutines, Gradle Kotlin DSL (build.gradle.kts), and AndroidManifest.xml. " +
            "Always produce clean, compilable code inside fenced ```kotlin, ```xml, or ```gradle code blocks, and explain key Android setup steps clearly.",
    ),
    DEBUGGER(
        displayName = "Code Debugger",
        shortLabel = "Debugger",
        description = "Pinpoint build errors, stack traces, and runtime bugs with minimal patches.",
        systemPrompt = "You are Nova Debugger, a diagnostic engineering assistant. " +
            "Analyze stack traces, Gradle errors, or source snippets, identify the exact root cause, and provide the corrected code in fenced code blocks.",
    ),
    SUMMARIZER(
        displayName = "Smart Distiller",
        shortLabel = "Distill",
        description = "Turn long articles, specs, or code files into crisp executive takeaways.",
        systemPrompt = "You are Nova Distiller. Condense complex text, technical docs, or code into clear bulleted takeaways, action items, and key facts.",
    );

    companion object {
        fun fromName(raw: String?): AiMode =
            entries.firstOrNull { it.name == raw } ?: CHAT
    }
}

enum class AiModelOption(
    val modelId: String,
    val displayName: String,
    val badge: String,
    val subtitle: String,
) {
    FLASH(
        modelId = "gemini-3.8-flash",
        displayName = "Nova Flash",
        badge = "Recommended",
        subtitle = "Fast frontier intelligence for chat, multimodal & APK coding",
    ),
    LITE(
        modelId = "gemini-3.5-flash-lite",
        displayName = "Nova Instant",
        badge = "Ultra Fast",
        subtitle = "Low-latency replies for quick edits & daily Q&A",
    ),
    PRO_CODER(
        modelId = "gemini-4-argon",
        displayName = "Nova Deep",
        badge = "Deep Logic",
        subtitle = "Complex STEM & multi-file Android architecture (Blaze plan)",
    );

    companion object {
        fun fromModelId(raw: String?): AiModelOption =
            entries.firstOrNull { it.modelId == raw } ?: FLASH
    }
}

data class StarterPrompt(
    val title: String,
    val subtitle: String,
    val prompt: String,
    val mode: AiMode,
)

val DEFAULT_STARTER_PROMPTS = listOf(
    StarterPrompt(
        title = "Build a Compose Screen",
        subtitle = "Material 3 scaffold, ViewModel & StateFlow",
        prompt = "Create a clean Jetpack Compose Material 3 screen with a TopAppBar, search bar, LazyColumn cards, and a ViewModel using StateFlow for an Android APK.",
        mode = AiMode.APK_CODER,
    ),
    StarterPrompt(
        title = "Fix Gradle APK Build",
        subtitle = "Troubleshoot dependencies, signing & manifest",
        prompt = "Walk me through the checklist to configure release signing, ProGuard rules, and AndroidManifest permissions so my Android APK compiles cleanly.",
        mode = AiMode.APK_CODER,
    ),
    StarterPrompt(
        title = "Debug a Crash Trace",
        subtitle = "Find root cause & patch Kotlin bugs fast",
        prompt = "I have an Android runtime crash. Ask me for the stack trace or code snippet, then explain the root cause and provide the exact Kotlin fix.",
        mode = AiMode.DEBUGGER,
    ),
    StarterPrompt(
        title = "Brainstorm & Write",
        subtitle = "Draft plans, scripts, or clear explanations",
        prompt = "Help me design a clear roadmap for launching a mobile product, including core MVP features, user onboarding, and key metrics.",
        mode = AiMode.CHAT,
    ),
)

@Keep
@IgnoreExtraProperties
data class ChatSession(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val mode: String = AiMode.CHAT.name,
    val modelId: String = AiModelOption.FLASH.modelId,
    val lastMessagePreview: String = "",
    val messageCount: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
) {
    fun resolvedMode(): AiMode = AiMode.fromName(mode)
    fun resolvedModel(): AiModelOption = AiModelOption.fromModelId(modelId)
}

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val userId: String = "",
    val role: String = "USER",
    val text: String = "",
    val hasImage: Boolean = false,
    val modelUsed: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
) {
    val isFromUser: Boolean get() = role == "USER"
}

@Keep
@IgnoreExtraProperties
data class CodeSnippet(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val language: String = "kotlin",
    val code: String = "",
    val notes: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null,
)
