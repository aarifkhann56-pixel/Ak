package com.example.data

import android.content.Context
import com.example.R
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.example.model.AiMode
import com.example.model.AiModelOption
import com.example.model.ChatMessage
import com.example.model.ChatSession
import com.example.model.CodeSnippet
import com.google.firebase.Firebase
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

class NovaRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        )
    )

    private fun requireUserId(expectedUid: String? = null): String {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        if (expectedUid != null) {
            check(uid == expectedUid) { "Signed-in user changed; refusing cross-user operation." }
        }
        return uid
    }

    fun observeChats(userId: String): Flow<List<ChatSession>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank." }
        val path = "users/$userId/chats"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("chats")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatSession::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeMessages(userId: String, chatId: String): Flow<List<ChatMessage>> = flow {
        require(userId.isNotBlank() && chatId.isNotBlank()) { "User ID and Chat ID must not be blank." }
        val path = "users/$userId/chats/$chatId/messages"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("chats")
                .document(chatId)
                .collection("messages")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        ChatMessage::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedBy { it.createdAt ?: Timestamp.now() }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    fun observeSnippets(userId: String): Flow<List<CodeSnippet>> = flow {
        require(userId.isNotBlank()) { "User ID must not be blank." }
        val path = "users/$userId/snippets"
        emitAll(
            db.collection("users")
                .document(userId)
                .collection("snippets")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        CodeSnippet::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.updatedAt ?: it.createdAt ?: Timestamp(0, 0) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == userId) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    suspend fun createChat(
        userId: String,
        title: String,
        mode: AiMode,
        model: AiModelOption,
        initialPreview: String,
    ): Result<String> {
        var docPath = "users/$userId/chats"
        return try {
            val uid = requireUserId(userId)
            val cleanTitle = title.trim().ifEmpty { "New Conversation" }.take(120)
            val cleanPreview = initialPreview.trim().take(300)
            val docRef = db.collection("users").document(uid).collection("chats").document()
            docPath = docRef.path
            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "mode" to mode.name,
                "modelId" to model.modelId,
                "lastMessagePreview" to cleanPreview,
                "messageCount" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateChatMeta(
        userId: String,
        chatId: String,
        mode: AiMode,
        model: AiModelOption,
        lastMessagePreview: String,
        messageCount: Long,
        newTitle: String? = null,
    ): Result<Unit> {
        var docPath = "users/$userId/chats/$chatId"
        return try {
            val uid = requireUserId(userId)
            require(chatId.isNotBlank()) { "Chat ID must not be blank." }
            val docRef = db.collection("users").document(uid).collection("chats").document(chatId)
            docPath = docRef.path
            val updates = mutableMapOf<String, Any>(
                "mode" to mode.name,
                "modelId" to model.modelId,
                "lastMessagePreview" to lastMessagePreview.trim().take(300),
                "messageCount" to messageCount.coerceIn(0L, 10_000L),
                "updatedAt" to FieldValue.serverTimestamp(),
            )
            if (!newTitle.isNullOrBlank()) {
                updates["title"] = newTitle.trim().take(120)
            }
            docRef.update(updates).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteChat(userId: String, chatId: String): Result<Unit> {
        var docPath = "users/$userId/chats/$chatId"
        return try {
            val uid = requireUserId(userId)
            require(chatId.isNotBlank()) { "Chat ID must not be blank." }
            val chatRef = db.collection("users").document(uid).collection("chats").document(chatId)
            docPath = chatRef.path
            val messagesSnap = chatRef.collection("messages").get().await()
            if (!messagesSnap.isEmpty) {
                val batch = db.batch()
                messagesSnap.documents.forEach { batch.delete(it.reference) }
                batch.commit().await()
            }
            chatRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun addMessage(
        userId: String,
        chatId: String,
        role: String,
        text: String,
        hasImage: Boolean,
        modelUsed: String? = null,
    ): Result<Unit> {
        var docPath = "users/$userId/chats/$chatId/messages"
        return try {
            val uid = requireUserId(userId)
            require(chatId.isNotBlank()) { "Chat ID must not be blank." }
            require(role == "USER" || role == "MODEL") { "Invalid role: $role" }
            val boundedText = text.trim().take(50_000)
            require(boundedText.isNotEmpty()) { "Message text must not be empty." }

            val docRef = db.collection("users")
                .document(uid)
                .collection("chats")
                .document(chatId)
                .collection("messages")
                .document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to uid,
                "role" to role,
                "text" to boundedText,
                "hasImage" to hasImage,
                "modelUsed" to modelUsed?.trim()?.take(64)?.ifEmpty { null },
                "createdAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null } as Map<String, Any>

            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun saveSnippet(
        userId: String,
        title: String,
        language: String,
        code: String,
        notes: String = "",
    ): Result<String> {
        var docPath = "users/$userId/snippets"
        return try {
            val uid = requireUserId(userId)
            val cleanTitle = title.trim().take(120)
            val cleanLang = language.trim().lowercase().ifEmpty { "kotlin" }.take(32)
            val cleanCode = code.trim().take(50_000)
            val cleanNotes = notes.trim().take(1000)
            require(cleanTitle.isNotEmpty()) { "Snippet title cannot be empty." }
            require(cleanCode.isNotEmpty()) { "Snippet code cannot be empty." }

            val docRef = db.collection("users").document(uid).collection("snippets").document()
            docPath = docRef.path

            val payload = mapOf(
                "userId" to uid,
                "title" to cleanTitle,
                "language" to cleanLang,
                "code" to cleanCode,
                "notes" to cleanNotes.ifEmpty { null },
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ).filterValues { it != null } as Map<String, Any>

            docRef.set(payload).await()
            Result.success(docRef.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun updateSnippet(
        userId: String,
        snippetId: String,
        title: String,
        language: String,
        code: String,
        notes: String = "",
    ): Result<Unit> {
        var docPath = "users/$userId/snippets/$snippetId"
        return try {
            val uid = requireUserId(userId)
            require(snippetId.isNotBlank()) { "Snippet ID must not be blank." }
            val cleanTitle = title.trim().take(120)
            val cleanLang = language.trim().lowercase().ifEmpty { "kotlin" }.take(32)
            val cleanCode = code.trim().take(50_000)
            val cleanNotes = notes.trim().take(1000)
            require(cleanTitle.isNotEmpty()) { "Snippet title cannot be empty." }
            require(cleanCode.isNotEmpty()) { "Snippet code cannot be empty." }

            val docRef = db.collection("users").document(uid).collection("snippets").document(snippetId)
            docPath = docRef.path
            docRef.update(
                mapOf(
                    "title" to cleanTitle,
                    "language" to cleanLang,
                    "code" to cleanCode,
                    "notes" to cleanNotes,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteSnippet(userId: String, snippetId: String): Result<Unit> {
        var docPath = "users/$userId/snippets/$snippetId"
        return try {
            val uid = requireUserId(userId)
            require(snippetId.isNotBlank()) { "Snippet ID must not be blank." }
            val docRef = db.collection("users").document(uid).collection("snippets").document(snippetId)
            docPath = docRef.path
            docRef.delete().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }
}
