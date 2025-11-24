package com.google.gmaillife.model;

public class EmailSummary {
    private String id;
    private String subject;
    private String from;
    private String date;
    private String snippet;

    // Required no-arg constructor for Jackson
    public EmailSummary() {}

    public EmailSummary(String id, String subject, String from, String date, String snippet) {
        this.id = id;
        this.subject = subject;
        this.from = from;
        this.date = date;
        this.snippet = snippet;
    }

    // Getters and setters (Jackson needs them)
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getFrom() { return from; }
    public void setFrom(String from) { this.from = from; }
    public String getDate() { return date; }
    public void setDate(String date) { this.date = date; }
    public String getSnippet() { return snippet; }
    public void setSnippet(String snippet) { this.snippet = snippet; }
}

