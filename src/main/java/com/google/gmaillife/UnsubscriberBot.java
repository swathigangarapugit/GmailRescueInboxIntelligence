package com.google.gmaillife;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.adk.tools.Annotations.Schema;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
import com.google.api.services.gmail.model.Thread;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnsubscriberBot {

    private final Gmail gmail;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public UnsubscriberBot(Gmail gmail) {
        this.gmail = gmail;
    }

    @Schema(name = "analyzeEmailBatch", description = "Analyze unread promotional emails")
    public Map<String, Object> analyzeEmailBatch() throws Exception {

        // Query promotional unread emails and fetch full metadata (headers may be in payload only in full)
        ListMessagesResponse response = gmail.users().messages()
                .list("me")
                .setQ("category:promotions is:unread")
                .setMaxResults(50L)
                .execute();

        List<Map<String,Object>> arr = new ArrayList<>();

        if (response.getMessages() != null) {
            for (Message msg : response.getMessages()) {
                Message full = gmail.users().messages()
                        .get("me", msg.getId())
                        .setFormat("full") // full to get headers consistently
                        .execute();

                Map<String,Object> item = new LinkedHashMap<>();
                item.put("id", msg.getId());
                item.put("subject", getHeader(full, "Subject"));
                item.put("from", getHeader(full, "From"));
                item.put("snippet", full.getSnippet());
                item.put("date", getHeader(full, "Date"));
                arr.add(item);
            }
        }

        return Map.of("items", arr);
    }

    @Schema(name = "trashEmail", description = "Trash an email by ID")
    public Map<String,Object> trashEmail(
            @Schema(name = "messageId", description = "ID of the email to trash")
            String messageId
    ) throws Exception {

        gmail.users().messages().trash("me", messageId).execute();

        return Map.of(
                "status", "ok",
                "id", messageId
        );
    }

    @Schema(name = "markAsRead", description = "Mark an email as read")
    public Map<String,Object> markAsRead(
            @Schema(name = "messageId") String messageId
    ) throws Exception {

        ModifyMessageRequest mods = new ModifyMessageRequest()
                .setRemoveLabelIds(List.of("UNREAD"));

        gmail.users().messages().modify("me", messageId, mods).execute();

        return Map.of(
                "status", "ok",
                "id", messageId
        );
    }

    @Schema(name = "archiveEmail", description = "Archive an email by ID")
    public Map<String, Object> archiveEmail(
            @Schema(description = "ID of the email to archive") String id
    ) throws Exception {

        ModifyMessageRequest req = new ModifyMessageRequest()
                .setRemoveLabelIds(List.of("INBOX"));

        gmail.users().messages().modify("me", id, req).execute();

        return Map.of("status", "ok", "id", id);
    }

    @Schema(name = "searchEmails", description = "Search emails")
    public Map<String, Object> searchEmails(
            @Schema(description = "Gmail search query") String query
    ) throws Exception {

        if (query == null || query.isBlank()) {
            query = "in:anywhere"; // safer default than from:me
        }

        long maxResults = 25L;

        var response = gmail.users().messages().list("me")
                .setQ(query)
                .setMaxResults(maxResults)
                .execute();

        List<Map<String,Object>> arr = new ArrayList<>();

        if (response.getMessages() != null) {
            for (var m : response.getMessages()) {
                var full = gmail.users().messages()
                        .get("me", m.getId())
                        .setFormat("full") // full to reliably get headers and body when needed
                        .execute();

                Map<String,Object> email = new LinkedHashMap<>();
                email.put("id", m.getId());
                email.put("subject", getHeader(full, "Subject"));
                email.put("from", getHeader(full, "From"));
                email.put("date", getHeader(full, "Date"));
                email.put("snippet", full.getSnippet());
                // don't put full body here (avoid large payloads)
                arr.add(email);
            }
        }

        return Map.of("items", arr);
    }

    @Schema(name = "getEmail", description = "Get full email")
    public Map<String, Object> getEmail(String messageId) throws Exception {

        Message message = gmail.users().messages().get("me", messageId).setFormat("full").execute();

        String body = extractBody(message);

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", message.getId());
        result.put("subject", getHeader(message,"Subject"));
        result.put("from", getHeader(message,"From"));
        result.put("date", getHeader(message,"Date"));
        result.put("snippet", message.getSnippet());
        result.put("body", body);

        return result;
    }

    @Schema(name = "getThread", description = "Get thread")
    public Map<String, Object> getThread(String threadId) throws Exception {

        Thread thread = gmail.users().threads().get("me", threadId).execute();
        List<Map<String,Object>> messages = new ArrayList<>();

        for (Message msg : thread.getMessages()) {

            Map<String,Object> one = new LinkedHashMap<>();
            one.put("id", msg.getId());
            one.put("subject", getHeader(msg,"Subject"));
            one.put("from", getHeader(msg,"From"));
            one.put("date", getHeader(msg,"Date"));
            one.put("snippet", msg.getSnippet());
            one.put("body", extractBody(msg));

            messages.add(one);
        }

        return Map.of("items", messages);
    }

    // robust header fetch
    private String getHeader(Message message, String name) {
        if (message == null || message.getPayload() == null || message.getPayload().getHeaders() == null) return "";
        return message.getPayload().getHeaders().stream()
                .filter(h -> name.equalsIgnoreCase(h.getName()))
                .map(MessagePartHeader::getValue)
                .findFirst()
                .orElse("");
    }

    // extract body: prefer HTML if possible, otherwise text
    private String extractBody(Message message) {
        try {
            if (message == null || message.getPayload() == null) return "";
            String html = getHtmlBodyFromMessage(message);
            if (html != null && !html.isBlank()) {
                // try to strip tags lightly for summary purposes
                return Jsoup.parse(html).text();
            }
            // fallback to concatenating text parts
            return extractParts(message.getPayload());
        } catch (Exception e) {
            return "";
        }
    }

    private String extractParts(MessagePart part) throws Exception {
        if (part == null) return "";

        StringBuilder sb = new StringBuilder();

        // If this part has body data
        if (part.getBody() != null && part.getBody().getData() != null) {
            try {
                byte[] data = Base64.getUrlDecoder().decode(part.getBody().getData());
                String decoded = new String(data, StandardCharsets.UTF_8);
                sb.append(decoded);
            } catch (IllegalArgumentException ignored) {
                // ignore bad base64; continue
            }
        }

        // Recurse multipart
        if (part.getParts() != null) {
            for (MessagePart p : part.getParts()) {
                sb.append(extractParts(p));
            }
        }

        return sb.toString();
    }

    @Schema(name = "unsubscribeEmail", description = "Unsubscribe user from a mailing list using message ID")
    public Map<String, Object> unsubscribeEmail(
            @Schema(description = "Gmail message ID") String messageId
    ) throws Exception {

        // 1) Load Gmail message (full required)
        Message msg = gmail.users().messages()
                .get("me", messageId)
                .setFormat("full")
                .execute();

        String header = getHeaderIgnoreCase(msg, "List-Unsubscribe");
        System.out.println("List-Unsubscribe header = " + header);

        String httpLink = null;
        String mailtoLink = null;

        if (header != null && !header.isBlank()) {
            Matcher m = Pattern.compile("<([^>]+)>").matcher(header);
            while (m.find()) {
                String link = m.group(1).trim();
                if (link.startsWith("http://") || link.startsWith("https://")) {
                    httpLink = link;
                } else if (link.startsWith("mailto:")) {
                    mailtoLink = link;
                }
            }
        }

        // 3) Try HTTP unsubscribe (preferred)
        if (httpLink != null) {
            Map<String, Object> res = tryHttpUnsubscribe(httpLink, messageId);
            if (res != null) return res;
        }

        // 4) Parse HTML body to find unsubscribe links
        String htmlBody = getHtmlBodyFromMessage(msg);
        if (htmlBody != null && !htmlBody.isBlank()) {
            String linkFromHtml = findUnsubscribeLinkInHtml(htmlBody);
            if (linkFromHtml != null) {
                Map<String, Object> res = tryHttpUnsubscribe(linkFromHtml, messageId);
                if (res != null) return res;
            }
        }

        // 5) Mailto fallback (send an email)
        if (mailtoLink != null) {
            try {
                boolean sent = sendMailtoUnsubscribe(mailtoLink);
                if (sent) {
                    return Map.of(
                            "status", "ok",
                            "method", "mailto",
                            "id", messageId,
                            "action", "unsubscribe-email-sent"
                    );
                } else {
                    return Map.of(
                            "status", "error",
                            "reason", "mailto-send-failed",
                            "mailto", mailtoLink
                    );
                }
            } catch (Exception e) {
                e.printStackTrace();
                return Map.of(
                        "status", "error",
                        "reason", "mailto-exception",
                        "message", e.getMessage(),
                        "mailto", mailtoLink
                );
            }
        }

        // 6) If HTML contained a mailto
        if (htmlBody != null && !htmlBody.isBlank()) {
            String mailtoFromHtml = findMailtoInHtml(htmlBody);
            if (mailtoFromHtml != null) {
                try {
                    boolean sent = sendMailtoUnsubscribe(mailtoFromHtml);
                    if (sent) {
                        return Map.of(
                                "status", "ok",
                                "method", "mailto",
                                "id", messageId,
                                "action", "unsubscribe-email-sent"
                        );
                    } else {
                        return Map.of(
                                "status", "error",
                                "reason", "mailto-send-failed",
                                "mailto", mailtoFromHtml
                        );
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    return Map.of(
                            "status", "error",
                            "reason", "mailto-exception",
                            "message", e.getMessage(),
                            "mailto", mailtoFromHtml
                    );
                }
            }
        }

        // 7) Nothing worked
        return Map.of(
                "status", "error",
                "reason", "no-valid-unsubscribe-method",
                "header", header
        );
    }

    // Try HTTP unsubscribe (GET then POST), return success map or null
    private Map<String, Object> tryHttpUnsubscribe(String urlStr, String messageId) {
        try {
            System.out.println("Trying HTTP unsubscribe: " + urlStr);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            // GET
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            String contentType = conn.getContentType();
            System.out.println("GET -> " + code + " Content-Type: " + contentType);

            if (isConfirmedUnsub(code, conn, contentType)) {
                return Map.of("status", "ok", "method", "http-get", "id", messageId, "action", "unsubscribed");
            }

            // POST attempt (some endpoints require POST)
            HttpURLConnection postConn = (HttpURLConnection) url.openConnection();
            postConn.setInstanceFollowRedirects(true);
            postConn.setConnectTimeout(8000);
            postConn.setReadTimeout(8000);
            postConn.setRequestMethod("POST");
            postConn.setDoOutput(true);
            // Try empty POST body
            int postCode = postConn.getResponseCode();
            String postCT = postConn.getContentType();
            System.out.println("POST -> " + postCode + " Content-Type: " + postCT);

            if (isConfirmedUnsub(postCode, postConn, postCT)) {
                return Map.of("status", "ok", "method", "http-post", "id", messageId, "action", "unsubscribed");
            }

        } catch (Exception e) {
            System.out.println("HTTP unsubscribe failed: " + e);
        }
        return null;
    }

    // Decide if response indicates a confirmed unsubscribe (avoid false positives)
    // Make conservative checks: 204/205 ok, JSON success ok, plain HTML only if explicit "you have been unsubscribed" visible
    private boolean isConfirmedUnsub(int code, HttpURLConnection conn, String contentType) {
        try {
            if (code == 204 || code == 205) return true;

            if (code >= 300 && code < 400) return false; // redirect — not a confirmation

            if (code == 200) {
                if (contentType != null && contentType.toLowerCase().contains("application/json")) {
                    try (InputStream is = conn.getInputStream()) {
                        String json = new String(is.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                        if (json.contains("unsubscribed") || json.contains("success") || json.contains("ok")) {
                            return true;
                        }
                    } catch (Exception ignore) {}
                }

                // For HTML, be conservative: only accept if explicit phrases appear
                if (contentType != null && contentType.toLowerCase().contains("text/html")) {
                    try (InputStream is = conn.getInputStream()) {
                        String body = new String(is.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                        if (body.contains("you have been unsubscribed") ||
                                body.contains("successfully unsubscribed") ||
                                body.contains("unsubscribed successfully") ||
                                body.contains("subscription cancelled")) {
                            return true;
                        }
                    } catch (Exception ignore) {}
                    return false;
                }

                // Unknown content-type: inspect body as fallback (conservative)
                try (InputStream is = conn.getInputStream()) {
                    String body = new String(is.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                    if (body.contains("you have been unsubscribed") ||
                            body.contains("unsubscribed successfully") ||
                            body.contains("success")) {
                        return true;
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            // treat failures as not confirmed
        }
        return false;
    }

    // Build and send mailto unsubscribe as an email via Gmail API (requires Gmail SEND scope)
    private boolean sendMailtoUnsubscribe(String mailtoLink) throws Exception {
        String to = mailtoLink.replaceFirst("mailto:", "").trim();
        int q = to.indexOf('?');
        if (q >= 0) to = to.substring(0, q);

        Properties props = new Properties();
        Session session = Session.getInstance(props);

        MimeMessage mime = new MimeMessage(session);
        mime.setFrom(new InternetAddress("me"));
        mime.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        mime.setSubject("Unsubscribe request");
        mime.setText("Please unsubscribe me from this mailing list.");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mime.writeTo(buffer);
        byte[] raw = buffer.toByteArray();
        String encoded = Base64.getUrlEncoder().encodeToString(raw);

        com.google.api.services.gmail.model.Message gmailMsg = new com.google.api.services.gmail.model.Message();
        gmailMsg.setRaw(encoded);

        // Note: this requires Gmail API scopes that include send, otherwise this will fail.
        gmail.users().messages().send("me", gmailMsg).execute();
        return true;
    }

    // Extract HTML body from Gmail Message payload, scanning nested parts
    private String getHtmlBodyFromMessage(com.google.api.services.gmail.model.Message message) {
        if (message == null || message.getPayload() == null) return null;

        Queue<com.google.api.services.gmail.model.MessagePart> q = new LinkedList<>();
        q.add(message.getPayload());

        while (!q.isEmpty()) {
            com.google.api.services.gmail.model.MessagePart part = q.poll();
            if (part.getParts() != null) {
                q.addAll(part.getParts());
            }
            String mimeType = part.getMimeType();
            com.google.api.services.gmail.model.MessagePartBody body = part.getBody();
            if (body == null || body.getData() == null) continue;

            try {
                String decoded = new String(Base64.getUrlDecoder().decode(body.getData()), StandardCharsets.UTF_8);
                if (mimeType != null && mimeType.toLowerCase().contains("html")) {
                    return decoded;
                }
                if (decoded.toLowerCase().contains("<html") || decoded.toLowerCase().contains("<a ")) {
                    return decoded;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    // Find unsubscribe HTTP link in HTML using Jsoup (conservative)
    private String findUnsubscribeLinkInHtml(String html) {
        if (html == null) return null;
        try {
            Document doc = Jsoup.parse(html);
            Elements anchors = doc.select("a[href]");
            for (Element a : anchors) {
                String href = a.attr("href");
                String text = a.text();
                String hrefLower = href == null ? "" : href.toLowerCase();
                String textLower = text == null ? "" : text.toLowerCase();

                boolean candidate = false;

                // prefer explicit anchor text
                if (textLower.contains("unsubscribe") || textLower.contains("opt out") || textLower.contains("manage preferences")) {
                    candidate = true;
                }
                // or explicit href containing unsubscribe
                if (hrefLower.contains("unsubscribe") || hrefLower.contains("optout") || hrefLower.contains("opt-out")) {
                    candidate = true;
                }
                // rel attribute
                if (a.hasAttr("rel") && a.attr("rel").toLowerCase().contains("unsub")) {
                    candidate = true;
                }

                if (candidate) {
                    // prefer http(s) links
                    if (hrefLower.startsWith("http://") || hrefLower.startsWith("https://")) {
                        return href;
                    }
                    if (hrefLower.startsWith("mailto:")) return href;
                    // sometimes links are relative; attempt to resolve absolute via base href if present (naive)
                    String base = doc.baseUri();
                    if (base != null && !base.isBlank() && href.startsWith("/")) {
                        return base + href;
                    }
                }
            }

            // fallback: look for any URL-like text containing 'unsubscribe'
            Pattern p = Pattern.compile("(https?://[^\\s\"'>]*unsubscribe[^\\s\"'>]*)", Pattern.CASE_INSENSITIVE);
            Matcher m = p.matcher(html);
            if (m.find()) return m.group(1);

        } catch (Exception e) {
            System.out.println("Jsoup parse error: " + e);
        }
        return null;
    }

    // Find mailto: inside HTML body (if present)
    private String findMailtoInHtml(String html) {
        if (html == null) return null;
        Pattern p = Pattern.compile("mailto:([\\w.%+-]+@[\\w.-]+\\.[A-Za-z]{2,})", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(html);
        if (m.find()) {
            return "mailto:" + m.group(1);
        }
        return null;
    }

    // Helper: case-insensitive header lookup
    private String getHeaderIgnoreCase(com.google.api.services.gmail.model.Message msg, String name) {
        if (msg == null || msg.getPayload() == null || msg.getPayload().getHeaders() == null) return null;
        for (com.google.api.services.gmail.model.MessagePartHeader h : msg.getPayload().getHeaders()) {
            if (h.getName() != null && h.getName().equalsIgnoreCase(name)) {
                return h.getValue();
            }
        }
        return null;
    }
}
