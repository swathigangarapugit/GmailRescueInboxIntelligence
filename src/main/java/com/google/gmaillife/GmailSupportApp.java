package com.google.gmaillife;

import com.google.adk.agents.LlmAgent;
import com.google.adk.agents.SequentialAgent;
import com.google.adk.agents.BaseAgent;
import com.google.adk.tools.FunctionTool;
import com.google.adk.web.AdkWebServer;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.GmailScopes;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class GmailSupportApp {

    private static final String APPLICATION_NAME = "Gmail Life Support";
    private static final String CREDENTIALS_FILE_PATH = "/credentials.json";

    public static void main(String[] args) throws Exception {
        final NetHttpTransport HTTP_TRANSPORT = GoogleNetHttpTransport.newTrustedTransport();
        Credential credential = getCredentials(HTTP_TRANSPORT);

        Gmail service = new Gmail.Builder(HTTP_TRANSPORT, GsonFactory.getDefaultInstance(), credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

        // BUILD FRESH AGENT WITH GMAIL SERVICE (no chaining on withGmail)
        BaseAgent rootAgent = createGmailAgent(service);

        // Start the ADK Dev UI
        AdkWebServer.start(rootAgent);
        System.out.println("Gmail Life Support is running at http://localhost:8080");
        System.out.println("Try: 'Clean my inbox' or 'Tell me my life story from Gmail'");

        // Keep app alive
        Thread.currentThread().join();
    }

    // FRESH BUILD — Inject Gmail directly, no static mutation
    public static BaseAgent createGmailAgent(Gmail gmail) {
        UnsubscriberBot tools = new UnsubscriberBot(gmail);
        ManageInbox manageTool = new ManageInbox(gmail);
        MailArchaeologist mailArchaeologist = new MailArchaeologist(gmail);
        // 1. Build your sub-agents
        LlmAgent analyzer = LlmAgent.builder()
                .name("analyzer")
                .model("gemini-2.5-flash")
                .instruction("""
You analyze unread promotional emails and return a JSON array of items:
[
  {"id":"<messageId>", "subject":"<subject>"}
]
When asked to "clean" or "show promos" or similar, call analyzeEmailBatch() and return the results.
""")
                .tools(List.of(FunctionTool.create(manageTool, "analyzeEmailBatch")))
                .build();

        // Decider: receives the analyzer array (or user instruction) and must output a JSON array of actions.
        LlmAgent decider = LlmAgent.builder()
                .name("decider")
                .model("gemini-2.5-flash")
                .instruction("""
Input: JSON array of email objects from analyzer or user selection.
Output: JSON array of actions. Each entry must be exactly:
[
  {"id":"<messageId>", "action":"trash"|"archive"|"markAsRead"}
]

Use "markAsRead" when the user intends to mark messages read (phrases: "mark as read", "mark them as read", "read it").
Do not output any natural language — only the JSON array.
""")
                .build();

        // Actor: performs the actions. Tools must include markAsRead.
        LlmAgent actor = LlmAgent.builder()
                .name("actor")
                .model("gemini-2.5-flash")
                .instruction("""
You execute the cleanup actions provided by decider. You MUST call the appropriate tool for each action:

- If action == "trash" → call trashEmail(messageId)
- If action == "archive" → call archiveEmail(messageId)
- If action == "markAsRead" → call markAsRead(messageId)

Do not produce plain text. Only call the tools and return the tool results.
""")
                .tools(List.of(
                        FunctionTool.create(manageTool, "trashEmail"),
                        FunctionTool.create(manageTool, "archiveEmail"),
                        FunctionTool.create(manageTool, "markAsRead")
                ))
                .build();
       /* LlmAgent lifeStory = LlmAgent.builder()
                .name("lifeStory")
                .model("gemini-2.5-flash")
                .instruction("""
                You write life stories using emails.
                ALWAYS call searchEmails, getEmail, or getThread.
                """)
                .tools(List.of(
                        FunctionTool.create(manageTool, "searchEmails"),
                        FunctionTool.create(manageTool, "getEmail"),
                        FunctionTool.create(manageTool, "getThread")
                ))
                .build();*/
        BaseAgent lifeStory = LifeStoryAgent.createAgent(gmail);

        LlmAgent scanInbox = LlmAgent.builder()
                .name("scanInbox")
                .model("gemini-2.5-flash")
                .instruction("""
                        You are scanning your mail box now.
                        Use scanMailbox.
                        Call tools when needed.
                """)
                .tools(List.of(
                        FunctionTool.create(manageTool, "searchEmails"),
                        FunctionTool.create(manageTool, "getEmail"),
                        FunctionTool.create(mailArchaeologist, "scanMailbox")
                ))
                .build();

        LlmAgent unSubscribe = LlmAgent.builder()
                .name("unSubscribe")
                .model("gemini-2.5-flash")
                .instruction("""
        You help users unsubscribe from emails.

        WHEN USER EXPRESSES AN UNSUBSCRIBE INTENT:
        - If the user says "unsubscribe from ___" or similar AND you have NOT yet
          used any tool in this conversation turn:
              → Call searchEmails immediately.

        STRICT WORKFLOW:
        1. FIRST tool call (only once):
              → searchEmails(query)

        2. SECOND tool call (only once):
              After searchEmails returns:
                  - If a messageId exists → call unsubscribeEmail(messageId)
                  - If NO message found → respond normally (no tools)

        TOOL RESULT HANDLING:
        - If the LAST message you see is a tool RESULT, it is NOT a new user request.
        - Do NOT call searchEmails again.
        - Do NOT call unsubscribeEmail again unless it is the single allowed follow-up.

        STATE TRACKING (IMPORTANT):
        You must track your own state during this conversation turn:
        - If you already called searchEmails, NEVER call it again.
        - If unsubscribeEmail was already called, NEVER call any tool again.
        - After ANY tool call, only one more tool call is allowed (unsubscribeEmail).

        LOOP PREVENTION:
        - Never repeat any tool.
        - Never chain tools beyond searchEmails → unsubscribeEmail.
        - If unsure, respond normally.
    """)
                .tools(List.of(
                        FunctionTool.create(manageTool, "searchEmails"),
                        FunctionTool.create(tools, "unsubscribeEmail")
                ))
                .build();


        LlmAgent router = LlmAgent.builder()
                .name("Gmail Life Support Router")
                .model("gemini-2.5-flash")
                .instruction("""
You are a router. Inspect the user's message and pick exactly one sub-agent to route to.

Routing rules (exact order):
1) If the user explicitly asks to unsubscribe (words: unsubscribe, stop emails, remove me):
   → route to "unSubscribe" (if present).

2) If the user mentions "mark" and "read" (e.g. "mark as read", "mark them as read", "mark it as read", "read it"):
   → route to "actor" (actor will run tools directly once supplied IDs or the decider output).

3) If the user asks to clean/organize inbox, mentions "promotions", "unread" or "clean my inbox":
   → route to "analyzer".

4) If user asks for life story, memory, timeline:
   → route to "lifeStory".

Otherwise ask for a clarifying question (but prefer routing to analyzer for inbox-cleaning questions).
""")
                // Only include the agents we created. Ensure "actor" is present so router can route mark-as-read.
                .subAgents(List.of(unSubscribe,analyzer, decider, actor,lifeStory,scanInbox))
                .build();
        return router;
    }

    private static Credential getCredentials(final NetHttpTransport transport) throws Exception {
        GoogleClientSecrets clientSecrets = GoogleClientSecrets.load(
                GsonFactory.getDefaultInstance(),
                new InputStreamReader(GmailSupportApp.class.getResourceAsStream(CREDENTIALS_FILE_PATH)));

        GoogleAuthorizationCodeFlow flow = new GoogleAuthorizationCodeFlow.Builder(
                transport, GsonFactory.getDefaultInstance(), clientSecrets,
                Collections.singleton(GmailScopes.GMAIL_MODIFY))
                .setDataStoreFactory(new FileDataStoreFactory(new java.io.File("tokens")))
                .setAccessType("offline")
                .build();

        LocalServerReceiver receiver = new LocalServerReceiver.Builder().setPort(8888).build();
        return new AuthorizationCodeInstalledApp(flow, receiver).authorize("user");
    }
}