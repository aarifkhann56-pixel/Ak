package com.example.data

import android.graphics.Bitmap
import com.example.model.AiMode
import com.example.model.AiModelOption
import com.example.model.ChatMessage
import com.google.firebase.Firebase
import com.google.firebase.ai.GenerativeModel
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.content
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout

class AiService {
    private val modelCache = mutableMapOf<Pair<String, AiMode>, GenerativeModel>()

    private fun getOrCreateModel(modelOption: AiModelOption, mode: AiMode): GenerativeModel {
        val key = modelOption.modelId to mode
        return modelCache.getOrPut(key) {
            Firebase.ai.generativeModel(
                modelName = modelOption.modelId,
                systemInstruction = content { text(mode.systemPrompt) },
            )
        }
    }

    fun streamReply(
        modelOption: AiModelOption,
        mode: AiMode,
        historyMessages: List<ChatMessage>,
        userPrompt: String,
        attachedBitmap: Bitmap?,
    ): Flow<String> {
        val model = getOrCreateModel(modelOption, mode)
        val sdkHistory = historyMessages.takeLast(20).map { msg ->
            val role = if (msg.isFromUser) "user" else "model"
            content(role) { text(msg.text) }
        }
        val chat = model.startChat(history = sdkHistory)
        val outgoing = content("user") {
            if (attachedBitmap != null) {
                image(attachedBitmap)
            }
            text(userPrompt)
        }
        return chat.sendMessageStream(outgoing).mapNotNull { chunk -> chunk.text }
    }

    suspend fun generateTitle(firstPrompt: String): String? {
        return try {
            val titleModel by lazy {
                Firebase.ai.generativeModel(
                    modelName = AiModelOption.LITE.modelId,
                    systemInstruction = content {
                        text("Generate a concise 3 to 5 word title summarizing this prompt. Output only the title without quotes or punctuation.")
                    }
                )
            }
            withTimeout(15_000L) {
                titleModel.generateContent(firstPrompt.take(300)).text?.trim()?.take(80)
            }
        } catch (_: Exception) {
            null
        }
    }
}
