const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_nova_123";
const BOB_UID = "bob_nova_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write chats, messages, or snippets", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const past = new Date(Date.now() - 2000);

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("chats").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("chats")
      .doc("chat_1")
      .set({
        userId: ALICE_UID,
        title: "Unauthorized",
        mode: "CHAT",
        modelId: "gemini-3.8-flash",
        lastMessagePreview: "hi",
        messageCount: 1,
        createdAt: past,
        updatedAt: past,
      })
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("chats")
      .doc("chat_1")
      .collection("messages")
      .get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("snippets").get()
  );
});

test("Authenticated owner: valid ChatSession create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const chatRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chats")
    .doc("chat_abc123");

  await assertSucceeds(
    chatRef.set({
      userId: ALICE_UID,
      title: "Compose M3 Scaffold",
      mode: "APK_CODER",
      modelId: "gemini-3.8-flash",
      lastMessagePreview: "How do I build an APK in Kotlin?",
      messageCount: 2,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("chats").get()
  );

  await assertSucceeds(
    chatRef.update({
      title: "Compose M3 Navigation",
      lastMessagePreview: "Here is the Navigation Compose setup...",
      messageCount: 4,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  // Reject immutable field mutation (createdAt) and ghost fields
  await assertFails(
    chatRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  await assertFails(
    chatRef.update({
      isAdmin: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertSucceeds(chatRef.delete());
});

test("ChatSession rejects @DocumentId id field, invalid modelId/mode, and future timestamps", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const chatsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chats");

  // Reject explicit id field in payload
  await assertFails(
    chatsCol.doc("chat_with_id").set({
      id: "chat_with_id",
      userId: ALICE_UID,
      title: "Bad ID",
      mode: "CHAT",
      modelId: "gemini-3.8-flash",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject unsupported modelId
  await assertFails(
    chatsCol.doc("chat_bad_model").set({
      userId: ALICE_UID,
      title: "Bad Model",
      mode: "CHAT",
      modelId: "gpt-4o",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: created,
      updatedAt: created,
    })
  );

  // Reject future timestamp
  await assertFails(
    chatsCol.doc("chat_future_ts").set({
      userId: ALICE_UID,
      title: "Future Time",
      mode: "CHAT",
      modelId: "gemini-3.8-flash",
      lastMessagePreview: "",
      messageCount: 0,
      createdAt: new Date(Date.now() + 60000),
      updatedAt: new Date(Date.now() + 60000),
    })
  );
});

test("Authenticated owner: ChatMessage subcollection create, list, immutable update denial, delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 3000);
  const msgsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chats")
    .doc("chat_1")
    .collection("messages");

  // User message without optional modelUsed
  await assertSucceeds(
    msgsCol.doc("msg_1").set({
      userId: ALICE_UID,
      role: "USER",
      text: "Write a Kotlin ViewModel",
      hasImage: false,
      createdAt: past,
    })
  );

  // Model message with optional modelUsed
  await assertSucceeds(
    msgsCol.doc("msg_2").set({
      userId: ALICE_UID,
      role: "MODEL",
      text: "```kotlin\nclass MainViewModel : ViewModel()\n```",
      hasImage: false,
      modelUsed: "gemini-3.8-flash",
      createdAt: past,
    })
  );

  await assertSucceeds(msgsCol.get());

  // Messages cannot be updated once written
  await assertFails(
    msgsCol.doc("msg_2").update({
      text: "Tampered response",
    })
  );

  await assertSucceeds(msgsCol.doc("msg_1").delete());
});

test("Authenticated owner: CodeSnippet create, list, partial update, validation, and delete", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const past = new Date(Date.now() - 4000);
  const snippetsCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("snippets");

  // Without optional notes
  await assertSucceeds(
    snippetsCol.doc("snip_1").set({
      userId: ALICE_UID,
      title: "Edge-to-Edge Scaffold",
      language: "kotlin",
      code: "Scaffold(contentWindowInsets = WindowInsets.safeDrawing) { }",
      createdAt: past,
      updatedAt: past,
    })
  );

  // Partial update adding notes
  await assertSucceeds(
    snippetsCol.doc("snip_1").update({
      notes: "Use in MainActivity",
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertSucceeds(snippetsCol.get());

  // Reject empty code
  await assertFails(
    snippetsCol.doc("snip_2").set({
      userId: ALICE_UID,
      title: "Empty",
      language: "kotlin",
      code: "",
      createdAt: past,
      updatedAt: past,
    })
  );

  await assertSucceeds(snippetsCol.doc("snip_1").delete());
});

test("Cross-user isolation: Bob cannot read or mutate Alice's chats, messages, or snippets", async () => {
  const past = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("chats")
      .doc("alice_chat")
      .set({
        userId: ALICE_UID,
        title: "Alice Private Chat",
        mode: "CHAT",
        modelId: "gemini-3.8-flash",
        lastMessagePreview: "Secret",
        messageCount: 1,
        createdAt: past,
        updatedAt: past,
      });
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("chats")
      .doc("alice_chat")
      .collection("messages")
      .doc("alice_msg")
      .set({
        userId: ALICE_UID,
        role: "USER",
        text: "Private prompt",
        hasImage: false,
        createdAt: past,
      });
    await db
      .collection("users")
      .doc(ALICE_UID)
      .collection("snippets")
      .doc("alice_snip")
      .set({
        userId: ALICE_UID,
        title: "Secret Key Helper",
        language: "kotlin",
        code: "val x = 1",
        createdAt: past,
        updatedAt: past,
      });
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const aliceChats = bobDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chats");

  await assertFails(aliceChats.get());
  await assertFails(aliceChats.doc("alice_chat").get());
  await assertFails(aliceChats.doc("alice_chat").update({ title: "Hijacked" }));
  await assertFails(aliceChats.doc("alice_chat").delete());
  await assertFails(aliceChats.doc("alice_chat").collection("messages").get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("snippets").get()
  );
  await assertFails(
    bobDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("snippets")
      .doc("alice_snip")
      .delete()
  );
});
