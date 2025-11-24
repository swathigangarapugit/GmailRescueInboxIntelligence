package com.google.gmaillife;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;
import com.google.api.services.gmail.Gmail;

import java.util.List;
import java.util.Map;

public class LifeStoryAgent {

    // Simple tool so agent can return a final report object


    // FIXED AGENT — This version *forces* tool use
    public static BaseAgent createAgent(Gmail gmail) {
        ManageInbox manageInbox = new ManageInbox(gmail);
        return LlmAgent.builder()
                .name("Life Story Agent")
                .model("gemini-2.5-flash")
                .instruction("""
You are a Gmail biographer. You MUST always begin by calling
searchEmails with a meaningful query to find important life events.

RULES YOU MUST FOLLOW:
1. ALWAYS call searchEmails first — never ask the user questions.
2. After getting results, call getEmail for the top important emails.
3. After reading emails, write a life-story summary.
4. NEVER respond with normal text before using at least one tool.
""")
                .tools(List.of(
                        FunctionTool.create(manageInbox, "searchEmails"),
                        FunctionTool.create(manageInbox, "getEmail"),
                        FunctionTool.create(manageInbox, "getThread")
                ))
                .build();
    }
}
