package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.auth.attemptAutoSignIn
import com.example.auth.authStateFlow
import com.example.auth.signOut
import com.example.data.AiService
import com.example.data.NovaRepository
import com.example.firebase.configureAppCheck
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.ChatScreen
import com.example.ui.screens.CodeVaultScreen
import com.example.ui.screens.HistoryDrawerSheet
import com.example.ui.theme.NovaAiTheme
import com.example.viewmodel.NovaViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

const val ROUTE_CHAT = "chat"
const val ROUTE_VAULT = "vault"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure App Check with launch intent before any UI or Firebase operations
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        setContent {
            NovaAiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppContent()
                }
            }
        }
    }
}

@Composable
fun AppContent(auth: FirebaseAuth = Firebase.auth) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val currentUser by auth.authStateFlow()
        .collectAsStateWithLifecycle(initialValue = auth.currentUser)

    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            attemptAutoSignIn(
                context = context,
                credentialManager = credentialManager,
                onAuthSuccess = {},
                onUnauthenticated = {},
                scope = scope,
            )
        }
    }

    val user = currentUser
    if (user == null) {
        AuthScreen(onAuthSuccess = {})
    } else {
        key(user.uid) {
            NovaAiRoot(
                currentUserId = user.uid,
                userDisplayName = user.displayName,
                userEmail = user.email,
                onSignOut = {
                    signOut(
                        credentialManager = credentialManager,
                        scope = scope,
                    )
                },
            )
        }
    }
}

@Composable
fun NovaAiRoot(
    currentUserId: String,
    userDisplayName: String?,
    userEmail: String?,
    onSignOut: () -> Unit,
) {
    val viewModel: NovaViewModel = viewModel(
        key = currentUserId,
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val databaseId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(databaseId)
                NovaViewModel(
                    repository = NovaRepository(db),
                    aiService = AiService(),
                    currentUserId = currentUserId,
                )
            }
        },
    )

    val chatsState by viewModel.chatsState.collectAsStateWithLifecycle()
    val snippetsState by viewModel.snippetsState.collectAsStateWithLifecycle()
    val messagesState by viewModel.messagesState.collectAsStateWithLifecycle()
    val activeChatId by viewModel.activeChatId.collectAsStateWithLifecycle()
    val selectedMode by viewModel.selectedMode.collectAsStateWithLifecycle()
    val selectedModel by viewModel.selectedModel.collectAsStateWithLifecycle()
    val composerText by viewModel.composerText.collectAsStateWithLifecycle()
    val attachedImage by viewModel.attachedImage.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val streamingText by viewModel.streamingText.collectAsStateWithLifecycle()
    val generationError by viewModel.generationError.collectAsStateWithLifecycle()
    val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()

    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_CHAT

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingSaveCode by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(statusBanner) {
        val msg = statusBanner
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearStatusBanner()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = currentRoute == ROUTE_CHAT,
        drawerContent = {
            HistoryDrawerSheet(
                chatsState = chatsState,
                activeChatId = activeChatId,
                userDisplayName = userDisplayName,
                userEmail = userEmail,
                onNewChat = {
                    viewModel.startNewChat()
                    scope.launch { drawerState.close() }
                },
                onSelectChat = { chat ->
                    viewModel.selectChat(chat)
                    scope.launch { drawerState.close() }
                },
                onDeleteChat = { chatId -> viewModel.deleteChat(chatId) },
                onSignOut = {
                    scope.launch {
                        drawerState.close()
                        onSignOut()
                    }
                },
            )
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val useRail = maxWidth >= 600.dp

            Scaffold(
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                bottomBar = {
                    if (!useRail) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_CHAT,
                                onClick = {
                                    navController.navigate(ROUTE_CHAT) {
                                        popUpTo(ROUTE_CHAT) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_CHAT) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome,
                                        contentDescription = stringResource(R.string.tab_chat),
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_chat)) },
                                modifier = Modifier.testTag("nav_tab_chat"),
                            )
                            NavigationBarItem(
                                selected = currentRoute == ROUTE_VAULT,
                                onClick = {
                                    navController.navigate(ROUTE_VAULT) {
                                        popUpTo(ROUTE_CHAT) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_VAULT) Icons.Rounded.Code else Icons.Outlined.Code,
                                        contentDescription = stringResource(R.string.tab_vault),
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_vault)) },
                                modifier = Modifier.testTag("nav_tab_vault"),
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .imePadding(),
                ) {
                    if (useRail) {
                        NavigationRail(
                            containerColor = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxHeight(),
                        ) {
                            NavigationRailItem(
                                selected = currentRoute == ROUTE_CHAT,
                                onClick = {
                                    navController.navigate(ROUTE_CHAT) {
                                        popUpTo(ROUTE_CHAT) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_CHAT) Icons.Rounded.AutoAwesome else Icons.Outlined.AutoAwesome,
                                        contentDescription = stringResource(R.string.tab_chat),
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_chat)) },
                            )
                            NavigationRailItem(
                                selected = currentRoute == ROUTE_VAULT,
                                onClick = {
                                    navController.navigate(ROUTE_VAULT) {
                                        popUpTo(ROUTE_CHAT) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = if (currentRoute == ROUTE_VAULT) Icons.Rounded.Code else Icons.Outlined.Code,
                                        contentDescription = stringResource(R.string.tab_vault),
                                    )
                                },
                                label = { Text(stringResource(R.string.tab_vault)) },
                            )
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = ROUTE_CHAT,
                        modifier = Modifier.weight(1f),
                    ) {
                        composable(ROUTE_CHAT) {
                            ChatScreen(
                                messagesState = messagesState,
                                selectedMode = selectedMode,
                                selectedModel = selectedModel,
                                composerText = composerText,
                                attachedImage = attachedImage,
                                isGenerating = isGenerating,
                                streamingText = streamingText,
                                generationError = generationError,
                                onOpenDrawer = { scope.launch { drawerState.open() } },
                                onNewChat = { viewModel.startNewChat() },
                                onModeChange = { viewModel.setMode(it) },
                                onModelChange = { viewModel.setModel(it) },
                                onComposerTextChange = { viewModel.onComposerTextChange(it) },
                                onAttachImage = { viewModel.setAttachedImage(it) },
                                onSendMessage = { viewModel.sendMessage() },
                                onStopGeneration = { viewModel.stopGeneration() },
                                onDismissError = { viewModel.clearGenerationError() },
                                onUseStarterPrompt = { prompt, mode ->
                                    viewModel.useStarterPrompt(prompt, mode)
                                },
                                onSaveCodeSnippet = { lang, code ->
                                    pendingSaveCode = lang to code
                                },
                            )
                        }
                        composable(ROUTE_VAULT) {
                            CodeVaultScreen(
                                snippetsState = snippetsState,
                                onSaveNewSnippet = { title, lang, code, notes ->
                                    viewModel.saveSnippet(title, lang, code, notes)
                                },
                                onUpdateSnippet = { id, title, lang, code, notes ->
                                    viewModel.updateSnippet(id, title, lang, code, notes)
                                },
                                onDeleteSnippet = { id -> viewModel.deleteSnippet(id) },
                                onAskAiAboutSnippet = { snippet ->
                                    viewModel.openSnippetInChat(snippet)
                                    navController.navigate(ROUTE_CHAT) {
                                        popUpTo(ROUTE_CHAT) { inclusive = false }
                                        launchSingleTop = true
                                    }
                                },
                                onShowFeedback = { msg ->
                                    scope.launch { snackbarHostState.showSnackbar(msg) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingSaveCode?.let { (lang, code) ->
        var title by remember(lang, code) {
            mutableStateOf("${lang.replaceFirstChar { it.uppercase() }} Snippet")
        }
        AlertDialog(
            onDismissRequest = { pendingSaveCode = null },
            title = { Text("Save to APK Code Vault") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(120) },
                    label = { Text("Snippet title") },
                    singleLine = true,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.saveSnippet(
                            title = title.ifBlank { "Code Snippet" },
                            language = lang,
                            code = code,
                        )
                        pendingSaveCode = null
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSaveCode = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}
