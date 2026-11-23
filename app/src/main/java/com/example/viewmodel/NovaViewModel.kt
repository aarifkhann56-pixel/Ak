package com.example.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiService
import com.example.data.NovaRepository
import com.example.model.AiMode
import com.example.model.AiModelOption
import com.example.model.ChatMessage
import com.example.model.ChatSession
import com.example.model.CodeSnippet
import com.example.model.UiState
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "NovaViewModel"
private const val STOP_TIMEOUT_MILLIS = 5000L
private const val INFERENCE_TIMEOUT_MILLIS = 60_000L

@OptIn(ExperimentalCoroutinesApi::class)
class NovaViewModel(
    private val repository: NovaRepository,
    private val aiService: AiService,
    private val currentUserId: String,
) : ViewModel() {

    val chatsState: StateFlow<UiState<List<ChatSession>>> = repository.observeChats(currentUserId)
        .map<List<ChatSession>, UiState<List<ChatSession>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing chats", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load conversations"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading,
        )

    val snippetsState: StateFlow<UiState<List<CodeSnippet>>> = repository.observeSnippets(currentUserId)
        .map<List<CodeSnippet>, UiState<List<CodeSnippet>>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing snippets", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load code vault"))
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Loading,
        )

    private val _activeChatId = MutableStateFlow<String?>(null)
    val activeChatId: StateFlow<String?> = _activeChatId.asStateFlow()

    val messagesState: StateFlow<UiState<List<ChatMessage>>> = _activeChatId
        .flatMapLatest { chatId ->
            if (chatId.isNullOrBlank()) {
                flowOf(UiState.Success(emptyList()))
            } else {
                repository.observeMessages(currentUserId, chatId)
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { error ->
                        val firestoreError = generateSequence(error) { it.cause }
                            .filterIsInstance<FirebaseFirestoreException>()
                            .firstOrNull()
                        if (firestoreError == null && error is CancellationException) throw error
                        val rootError = firestoreError ?: error
                        Log.w(TAG, "Error observing messages", rootError)
                        emit(UiState.Error(rootError.message ?: "Failed to load messages"))
                    }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = UiState.Success(emptyList()),
        )

    private val _selectedMode = MutableStateFlow(AiMode.APK_CODER)
    val selectedMode: StateFlow<AiMode> = _selectedMode.asStateFlow()

    private val _selectedModel = MutableStateFlow(AiModelOption.FLASH)
    val selectedModel: StateFlow<AiModelOption> = _selectedModel.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _attachedImage = MutableStateFlow<Bitmap?>(null)
    val attachedImage: StateFlow<Bitmap?> = _attachedImage.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingText = MutableStateFlow("")
    val streamingText: StateFlow<String> = _streamingText.asStateFlow()

    private val _generationError = MutableStateFlow<String?>(null)
    val generationError: StateFlow<String?> = _generationError.asStateFlow()

    private val _statusBanner = MutableStateFlow<String?>(null)
    val statusBanner: StateFlow<String?> = _statusBanner.asStateFlow()

    private var generationJob: Job? = null

    fun onComposerTextChange(value: String) {
        _composerText.value = value
    }

    fun setMode(mode: AiMode) {
        _selectedMode.value = mode
    }

    fun setModel(model: AiModelOption) {
        _selectedModel.value = model
    }

    fun setAttachedImage(bitmap: Bitmap?) {
        _attachedImage.value = bitmap
    }

    fun clearStatusBanner() {
        _statusBanner.value = null
    }

    fun clearGenerationError() {
        _generationError.value = null
    }

    fun startNewChat() {
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
        _generationError.value = null
        _activeChatId.value = null
    }

    fun selectChat(chat: ChatSession) {
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
        _generationError.value = null
        _activeChatId.value = chat.id
        _selectedMode.value = chat.resolvedMode()
        _selectedModel.value = chat.resolvedModel()
    }

    fun useStarterPrompt(prompt: String, mode: AiMode) {
        _selectedMode.value = mode
        _composerText.value = prompt
    }

    fun openSnippetInChat(snippet: CodeSnippet) {
        startNewChat()
        _selectedMode.value = AiMode.APK_CODER
        _composerText.value = buildString {
            appendLine("Review, explain, and suggest improvements for this ${snippet.language} snippet (${snippet.title}):")
            appendLine("```${snippet.language}")
            appendLine(snippet.code)
            append("```")
        }
    }

    fun sendMessage(overridePrompt: String? = null) {
        if (_isGenerating.value) return
        val prompt = (overridePrompt ?: _composerText.value).trim()
        val bitmap = _attachedImage.value
        if (prompt.isEmpty() && bitmap == null) return

        val effectivePrompt = prompt.ifEmpty { "Analyze this attached image and help me with it." }
        if (overridePrompt == null) {
            _composerText.value = ""
            _attachedImage.value = null
        }
        _generationError.value = null

        val mode = _selectedMode.value
        val model = _selectedModel.value
        val priorMessages = (messagesState.value as? UiState.Success)?.data ?: emptyList()

        generationJob = viewModelScope.launch {
            _isGenerating.value = true
            _streamingText.value = ""
            val accumulated = StringBuilder()

            try {
                var chatId = _activeChatId.value
                val isFirstTurn = chatId.isNullOrBlank()
                if (isFirstTurn) {
                    val fallbackTitle = effectivePrompt.lines().first().take(48)
                    val createdResult = repository.createChat(
                        userId = currentUserId,
                        title = fallbackTitle,
                        mode = mode,
                        model = model,
                        initialPreview = effectivePrompt,
                    )
                    if (createdResult.isFailure) {
                        _generationError.value = createdResult.exceptionOrNull()?.localizedMessage
                            ?: "Could not start cloud conversation."
                        _isGenerating.value = false
                        return@launch
                    }
                    chatId = createdResult.getOrThrow()
                    _activeChatId.value = chatId
                }

                val resolvedChatId = chatId!!
                val userWrite = repository.addMessage(
                    userId = currentUserId,
                    chatId = resolvedChatId,
                    role = "USER",
                    text = effectivePrompt,
                    hasImage = bitmap != null,
                )
                if (userWrite.isFailure) {
                    _generationError.value = userWrite.exceptionOrNull()?.localizedMessage
                        ?: "Failed to save message."
                    _isGenerating.value = false
                    return@launch
                }

                withTimeout(INFERENCE_TIMEOUT_MILLIS) {
                    aiService.streamReply(
                        modelOption = model,
                        mode = mode,
                        historyMessages = priorMessages,
                        userPrompt = effectivePrompt,
                        attachedBitmap = bitmap,
                    ).collect { chunk ->
                        accumulated.append(chunk)
                        _streamingText.value = accumulated.toString()
                    }
                }

                val finalReply = accumulated.toString().trim()
                if (finalReply.isEmpty()) {
                    _generationError.value = "Nova AI returned an empty response. Please try again."
                } else {
                    val modelWrite = repository.addMessage(
                        userId = currentUserId,
                        chatId = resolvedChatId,
                        role = "MODEL",
                        text = finalReply,
                        hasImage = false,
                        modelUsed = model.modelId,
                    )
                    if (modelWrite.isFailure) {
                        _generationError.value = modelWrite.exceptionOrNull()?.localizedMessage
                            ?: "Response received, but failed to sync to cloud."
                    } else {
                        val smartTitle = if (isFirstTurn) aiService.generateTitle(effectivePrompt) else null
                        repository.updateChatMeta(
                            userId = currentUserId,
                            chatId = resolvedChatId,
                            mode = mode,
                            model = model,
                            lastMessagePreview = finalReply.replace("\n", " ").take(140),
                            messageCount = (priorMessages.size + 2).toLong(),
                            newTitle = smartTitle,
                        )
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "AI streaming timed out", e)
                _generationError.value = "Response timed out after 60 seconds. Try Nova Flash or shorten the prompt."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "AI generation failed", e)
                val hint = if (model == AiModelOption.PRO_CODER) {
                    " (Note: Nova Deep uses gemini-4-argon which requires Blaze billing; try switching to Nova Flash.)"
                } else {
                    ""
                }
                _generationError.value = (e.localizedMessage ?: "AI request failed.") + hint
            } finally {
                _streamingText.value = ""
                _isGenerating.value = false
            }
        }
    }

    fun stopGeneration() {
        val partial = _streamingText.value.trim()
        val chatId = _activeChatId.value
        generationJob?.cancel()
        _isGenerating.value = false
        _streamingText.value = ""
        if (partial.isNotEmpty() && !chatId.isNullOrBlank()) {
            viewModelScope.launch {
                repository.addMessage(
                    userId = currentUserId,
                    chatId = chatId,
                    role = "MODEL",
                    text = "$partial\n\n_(stopped)_",
                    hasImage = false,
                    modelUsed = _selectedModel.value.modelId,
                )
            }
        }
    }

    fun deleteChat(chatId: String) {
        viewModelScope.launch {
            val result = repository.deleteChat(currentUserId, chatId)
            if (result.isSuccess) {
                if (_activeChatId.value == chatId) {
                    startNewChat()
                }
                _statusBanner.value = "Conversation deleted"
            } else {
                _statusBanner.value = result.exceptionOrNull()?.localizedMessage ?: "Delete failed"
            }
        }
    }

    fun saveSnippet(title: String, language: String, code: String, notes: String = "") {
        viewModelScope.launch {
            val result = repository.saveSnippet(
                userId = currentUserId,
                title = title,
                language = language,
                code = code,
                notes = notes,
            )
            _statusBanner.value = if (result.isSuccess) {
                "Saved to APK Code Vault"
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Could not save snippet"
            }
        }
    }

    fun updateSnippet(snippetId: String, title: String, language: String, code: String, notes: String) {
        viewModelScope.launch {
            val result = repository.updateSnippet(
                userId = currentUserId,
                snippetId = snippetId,
                title = title,
                language = language,
                code = code,
                notes = notes,
            )
            _statusBanner.value = if (result.isSuccess) {
                "Snippet updated"
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Could not update snippet"
            }
        }
    }

    fun deleteSnippet(snippetId: String) {
        viewModelScope.launch {
            val result = repository.deleteSnippet(currentUserId, snippetId)
            _statusBanner.value = if (result.isSuccess) {
                "Snippet removed"
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Delete failed"
            }
        }
    }
}
