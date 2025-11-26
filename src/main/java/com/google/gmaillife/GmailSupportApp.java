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

        // 1. Build your sub-agents
        LlmAgent analyzer = LlmAgent.builder()
                .name("analyzer")
                .model("gemini-2.5-flash")
                .instruction("""
                        .instruction(""\"
                                                                              You are the ANALYZER.
                                                                              
                                                                              YOUR JOB:
                                                                              - Call analyzeEmailBatch exactly once.
                                                                              - Always call it with this fixed input:
                                                                                                          {
                                                                                                            "query": "in:inbox newer_than:2d"
                                                                                                          }
                                                                              - Return ONLY the raw JSON map produced by analyzeEmailBatch.
                                                                              - No commentary, no text, no explanations, no formatting.
                                                                              
                                                                              STRICT RULES:
                                                                              1. You MUST call analyzeEmailBatch exactly once.
                                                                              2. After the tool returns, you MUST return its JSON output EXACTLY as-is.
                                                                              3. DO NOT add any words before or after the JSON.
                                                                              4. DO NOT wrap it in a sentence.
                                                                              5. DO NOT modify the fields.
                                                                              6. DO NOT think out loud.
                                                                              7. DO NOT call any other tools.
                                                                              8. After returning the JSON map, STOP COMPLETELY.
                                                                              
                                                                              If you violate ANY of these, the system will break.
                                                                              ""\")
                                                                              

""")
                .tools(List.of(FunctionTool.create(tools, "analyzeEmailBatch")))
                .build();

        LlmAgent decider = LlmAgent.builder()
                .name("decider")
                .model("gemini-2.5-flash")
                .instruction("""
You are the DECIDER.

IMPORTANT:
- DO NOT choose trash/archive/markasread yourself.
- DO NOT guess user intent.
- DO NOT infer or assume actions.
- If the user did NOT explicitly say "trash", "archive", or "mark as read"
  in THIS SAME USER MESSAGE:
      → You MUST ask the user what action to take.
      → You MUST include the message IDs in your question.
      → You MUST NOT choose any actions automatically.

Rules:
1) Do NOT call any tools.
2) Input is the Map from analyzer (email summaries).
3) First, determine if the user explicitly requested an action:
      - If YES → return decisions normally.
      - If NO → return a follow-up question asking what action to perform.
4) Output format when returning decisions:

{
 "decisions": [
   {"id":"...", "action":"trash/archive/markasread/keep"}
 ]
}

5) Output format when asking the user:

{
  "requires_followup": true,
  "question": "I found these emails: [IDS]. What action should I take? (trash / archive / mark as read / keep)"
}

6) NEVER auto-select trash/archive/markasread.
7) ALWAYS ask the user when the intent is missing.
""")
                .build();


        LlmAgent actor = LlmAgent.builder()
                .name("actor")
                .model("gemini-2.5-flash")
                .instruction("""
You execute trash or archive actions.

RULES:
1) For each decision:
   - If action=trash → call trashEmail(messageId)
   - If action=archive → call archiveEmail(messageId)
2) Call each tool EXACTLY ONCE if needed.
3) Never call searchEmails or any other tools.

4) After all actions, return a FINAL STRING summary:
   "Inbox cleanup complete. Processed <N> messages."

5) DO NOT return JSON, Maps, or objects.
6) After returning the string, STOP.
""")
                .tools(List.of(
                        FunctionTool.create(tools, "trashEmail"),
                        FunctionTool.create(tools, "archiveEmail")
                ))
                .build();


        // Multi-step deterministic flow for cleanup
        SequentialAgent cleanupFlow = SequentialAgent.builder()
                .name("cleanupFlow")
                .subAgents(List.of(analyzer,decider,actor))
                // <-- ADD THIS
                .build();

        LlmAgent lifeStory = LlmAgent.builder()
                .name("lifeStory")
                .model("gemini-2.5-flash")
                .instruction("""
                        You are a biographer. When the user asks about "life story", "timeline",
                        "biggest moments", "memories", "my journey", or "what my emails say about me":
                                                
                        RULES:
                        1) Call searchEmails EXACTLY ONCE with a broad query such as\s
                           "label:all newer_than:5y".
                                                
                        2) DO NOT call getEmail or getThread. Ignore those tools completely.
                                                
                        3) Use ONLY the information returned from searchEmails
                           (subject, from, date, snippet). Do not attempt to fetch full bodies.
                                                
                        4) Write a readable, flowing narrative paragraph that summarizes what the
                           user's emails say about their life — highlighting themes, milestones,
                           patterns, or personal moments inferred from the metadata.
                                                
                        5) The final response must be a plain paragraph of text.
                           No JSON. No braces. No lists. No technical formatting.
                                                
                        6) After generating the paragraph, STOP.\s
                           Do not call any additional tools.\s
                           Do not retry searchEmails.\s
                           Do not loop.
                                                
                        7) After producing the final response:
                           - DO NOT call any tool again
                           - DO NOT retry tool calls
                           - DO NOT attempt to expand details

                        Your total tool calls must be ONE (1).

                        """)
                .tools(List.of(
                        FunctionTool.create(tools, "searchEmails"),
                        FunctionTool.create(tools, "getEmail"),
                        FunctionTool.create(tools, "getThread")
                ))
                .build();

        LlmAgent trashAgent = LlmAgent.builder()
                .name("trashAgent")
                .model("gemini-2.5-flash")
                .instruction("""
When user asks to delete/trash:

1) Call searchEmails once.
2) Call trashEmail(messageId) exactly once.
3) Return a Map confirmation and STOP.

No more tool calls.

""")
                .tools(List.of(FunctionTool.create(tools, "searchEmails"), FunctionTool.create(tools, "trashEmail")))
                .build();

        LlmAgent archiveAgent = LlmAgent.builder()
                .name("archiveAgent")
                .model("gemini-2.5-flash")
                .instruction("""
When user asks to archive a single message:

1) Call searchEmails once to find the message.
2) Call archiveEmail(messageId) exactly once.
3) Return a Map confirmation and STOP.

Do NOT retry tools.
Do NOT call any extra tools.

""")
                .tools(List.of(FunctionTool.create(tools, "searchEmails"), FunctionTool.create(tools, "archiveEmail")))
                .build();

        LlmAgent markAsReadAgent = LlmAgent.builder()
                .name("markAsReadAgent")
                .model("gemini-2.5-flash")
                .instruction("""
When user asks to mark an email as read:

1) Call searchEmails once.
2) Call markAsRead(messageId) exactly once.
3) Return a Map confirmation and STOP.

Do not retry or call extra tools.

""")
                .tools(List.of(FunctionTool.create(tools, "searchEmails"), FunctionTool.create(tools, "markAsRead")))
                .build();


        LlmAgent unSubscribe = LlmAgent.builder()
                .name("unSubscribe")
                .model("gemini-2.5-flash")
                .instruction("""
When user asks to unsubscribe:

1) Call searchEmails once with the user's query.
2) If results exist:
   - Call unsubscribeEmail(messageId) exactly once.
3) After the unsubscribeEmail tool call, you MUST return a final 
   natural-language confirmation message such as:
   "You have been unsubscribed from <sender>."
   Do NOT return the raw result Map.
   Do NOT end the response with a tool call.

Do NOT retry searchEmails.
Do NOT loop or call any other tools.
STOP after sending the final confirmation message.
""")

                .tools(List.of(
                        FunctionTool.create(tools, "searchEmails"),
                        FunctionTool.create(tools, "unsubscribeEmail")
                ))
                .build();

        // Debug-friendly deterministic router with logging + clarify fallback
        LlmAgent clarifyAgent = LlmAgent.builder()
                .name("clarify")
                .model("gemini-2.5-flash")
                .instruction("""
When the router returns "clarify", ask a single short clarifying question to the user that helps routing
(e.g., "Do you want me to unsubscribe, archive, or delete messages?"). Return a short text answer Map: { "text": "<question>" }.
""")
                .build();


        // 2. ROOT AGENT with routing instructions (Java ADK way)
        LlmAgent router = LlmAgent.builder()
                .name("Gmail Life Support")
                .model("gemini-2.5-flash")
                .instruction("""
You are a STRICT deterministic router.

IMPORTANT:
- Only look at the USER'S ORIGINAL INPUT MESSAGE. role: user
- Ignore any agent output, JSON, tool results, or system prompts.
- Ignore anything that did NOT come directly from the human user.

Convert ONLY the user message to lowercase and route according to:

(keep your same routing rules here)

Return exactly one of the following tokens:
unSubscribe, cleanupFlow, lifeStory, archiveAgent, trashAgent, markAsReadAgent, clarify

Return ONLY the token and nothing else.

""")
                .subAgents(List.of(unSubscribe, archiveAgent, trashAgent, markAsReadAgent, cleanupFlow, lifeStory, clarifyAgent))
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
