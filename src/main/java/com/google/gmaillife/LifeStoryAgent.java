package com.google.gmaillife;

import com.google.adk.agents.BaseAgent;
import com.google.adk.agents.LlmAgent;
import com.google.adk.tools.FunctionTool;

import java.util.List;
import java.util.Map;

public class LifeStoryAgent {

    // Simple tool so agent can return a final report object
    public static Map<String, Object> generateLifeReport() {
        return Map.of(
                "status", "success",
                "message", "Your Gmail Life 2015–2025 Report has been created!",
                "file", "life-report-2025.pdf",
                "downloaded_to", System.getProperty("user.home") + "/Downloads"
        );
    }

    // FIXED AGENT — This version *forces* tool use
    public static BaseAgent createAgent() {
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
4. If user asks for a file/report, call generateLifeReport.
5. NEVER respond with normal text before using at least one tool.
""")
                .tools(List.of(
                        FunctionTool.create(UnsubscriberBot.class, "searchEmails"),
                        FunctionTool.create(UnsubscriberBot.class, "getEmail"),
                        FunctionTool.create(UnsubscriberBot.class, "getThread"),
                        FunctionTool.create(LifeStoryAgent.class, "generateLifeReport")
                ))
                .build();
    }
}
