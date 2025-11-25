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
You analyze unread promotional emails.
Call the tool analyzeEmailBatch when cleaning or finding unread/promo emails.
""")
                .tools(List.of(FunctionTool.create(tools, "analyzeEmailBatch")))
                .build();

        LlmAgent decider = LlmAgent.builder()
                .name("decider")
                .model("gemini-2.5-flash")
                .instruction("""
                        You decide trash/archive/keep for emails. If user asks about cleaning or organizing → decide actions. Otherwise, pass to next agent.""")
                .build();

        LlmAgent actor = LlmAgent.builder()
                .name("actor")
                .model("gemini-2.5-flash")
                .instruction("""
                You execute trashEmail and archiveEmail. If user asks about cleaning or organizing → execute actions. Otherwise, pass to next agent.""")
                .tools(List.of(
                        FunctionTool.create(tools, "trashEmail"),
                        FunctionTool.create(tools, "archiveEmail")
                ))
                .build();

        LlmAgent lifeStory = LlmAgent.builder()
                .name("lifeStory")
                .model("gemini-2.5-flash")
                .instruction("""
You are a biographer. If user asks about "life story", "timeline", 
"biggest moments", "my journey", "personal history", "what my emails say about me" →
use searchEmails...
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
You trash a single email. When the user asks to delete, move to trash, or remove an email,
call the tool trashEmail with the messageId. Return a short confirmation after success.
Do not call other tools.
""")
                .tools(List.of(FunctionTool.create(tools, "trashEmail")))
                .build();

        LlmAgent archiveAgent = LlmAgent.builder()
                .name("archiveAgent")
                .model("gemini-2.5-flash")
                .instruction("""
You archive a single email. When the user asks to archive, move out of inbox, or file away an email,
call the tool archiveEmail with the messageId. Return a short confirmation after success.
Do not call other tools.
""")
                .tools(List.of(FunctionTool.create(tools, "archiveEmail")))
                .build();

        LlmAgent markAsReadAgent = LlmAgent.builder()
                .name("markAsReadAgent")
                .model("gemini-2.5-flash")
                .instruction("""
You mark an email as read. When the user asks to 'mark as read', 'mark read', 'read this', or similar,
call the tool markAsRead with the messageId. Return a short confirmation after success.
Do not call other tools.
""")
                .tools(List.of(FunctionTool.create(tools, "markAsRead")))
                .build();


        LlmAgent unSubscribe = LlmAgent.builder()
                .name("unSubscribe")
                .model("gemini-2.5-flash")
                .instruction("""
                You help users unsubscribe from emails.

                When user says "unsubscribe from ___":
                    1. Call searchEmails immediately.
                    2. When searchEmails returns a messageId:
                         → call unsubscribeEmail.
                    3. If no message found:
                         → respond normally.

                No retries. No loops. No repeating tools.
        """)
                .tools(List.of(
                        FunctionTool.create(tools, "searchEmails"),
                        FunctionTool.create(tools, "unsubscribeEmail")
                ))
                .build();


        // 2. ROOT AGENT with routing instructions (Java ADK way)
        LlmAgent router = LlmAgent.builder()
                .name("Gmail Life Support")
                .model("gemini-2.5-flash")
                .instruction("""
                        You are a router agent.
                        
                        If the user asks to unsubscribe from anything (e.g. “unsubscribe”,\s
                                        “stop emails”, “remove me from mailing list”, “cancel newsletter”):
                                            → route to "unSubscribe".
                        If the user asks to archive, move to archive, file away, or "put in archive":
                               → route to "archiveAgent".
                                                
                        If the user asks to delete, trash, "move to trash", "delete this message", or similar:
                               → route to "trashAgent".
                                                
                        If the user asks to mark as read, mark read, "mark this as read", or "read this":
                               → route to "markAsReadAgent".
                                                
                        If user asks about cleaning inbox, spam, organizing, unread →
                        route to "analyzer".
                                                
                        If user asks about life story or memories →
                        route to "lifeStory".
                                                
                        Otherwise ask clarifying questions.
                                              
                        """)
                .subAgents(List.of(unSubscribe,   archiveAgent,
                        trashAgent,
                        markAsReadAgent,analyzer, decider, actor, lifeStory))
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
