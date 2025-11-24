package com.google.gmaillife;

import com.google.adk.tools.Annotations.Schema;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.*;
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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnsubscriberBot {

    private final Gmail gmail;

    public UnsubscriberBot(Gmail gmail) {
        this.gmail = gmail;
    }

    @Schema(name = "unsubscribeEmail", description = "Unsubscribe user from a mailing list using message ID")
    public Map<String, Object> unsubscribeEmail(
            @Schema(description = "Gmail message ID") String messageId
    ) throws Exception {

        // 1) Load Gmail message
        Message msg = gmail.users().messages()
                .get("me", messageId)
                .setFormat("full")
                .execute();

        // 2) Try List-Unsubscribe header first
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

        // 3) If header HTTP link exists, try it (highest priority)
        if (httpLink != null) {
            Map<String, Object> res = tryHttpUnsubscribe(httpLink, messageId);
            if (res != null) return res;
        }

        // 4) If no header HTTP link, try to parse the HTML body to find unsubscribe links
        String htmlBody = getHtmlBodyFromMessage(msg);
        if (htmlBody != null && !htmlBody.isBlank()) {
            String linkFromHtml = findUnsubscribeLinkInHtml(htmlBody);
            if (linkFromHtml != null) {
                System.out.println("Found unsubscribe link in HTML body: " + linkFromHtml);
                Map<String, Object> res = tryHttpUnsubscribe(linkFromHtml, messageId);
                if (res != null) return res;
            }
        }

        // 5) If header only contains mailto or we found a mailto in header, send mailto-unsubscribe via Gmail API
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
            System.out.println("GET -> " + code + " Content-Type: " + conn.getContentType());

            if (isConfirmedUnsub(code, conn)) {
                return Map.of(
                        "status", "ok",
                        "method", "http-get",
                        "id", messageId,
                        "action", "unsubscribed"
                );
            }

            // POST (some endpoints require POST)
            conn = (HttpURLConnection) url.openConnection();
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            // Optionally write an empty body or typical form data; most endpoints accept empty POST
            int postCode = conn.getResponseCode();
            System.out.println("POST -> " + postCode + " Content-Type: " + conn.getContentType());

            if (isConfirmedUnsub(postCode, conn)) {
                return Map.of(
                        "status", "ok",
                        "method", "http-post",
                        "id", messageId,
                        "action", "unsubscribed"
                );
            }

        } catch (Exception e) {
            System.out.println("HTTP unsubscribe failed: " + e);
        }
        return null;
    }


    // Decide if response indicates a confirmed unsubscribe (avoid false positives)
    private boolean isConfirmedUnsub(int code, HttpURLConnection conn) throws Exception {
        if (code == 204 || code == 205) return true;

        // 200 may be ok only if NOT HTML and contains JSON success or explicit keywords
        if (code == 200) {
            String ct = conn.getContentType();
            if (ct != null && ct.toLowerCase().contains("text/html")) {
                // HTML landing pages are NOT considered an automatic unsubscribe
                return false;
            }

            if (ct != null && ct.toLowerCase().contains("application/json")) {
                try (InputStream is = conn.getInputStream()) {
                    String json = new String(is.readAllBytes());
                    String js = json.toLowerCase();
                    if (js.contains("unsubscribed") || js.contains("success") || js.contains("ok")) {
                        return true;
                    }
                } catch (Exception ignore) {}
            }

            // If content-type unknown but body contains explicit keywords
            try (InputStream is = conn.getInputStream()) {
                String body = new String(is.readAllBytes()).toLowerCase();
                if (body.contains("unsubscribed") || body.contains("you have been unsubscribed") || body.contains("success")) {
                    return true;
                }
            } catch (Exception ignore) {}
        }

        // 3xx redirects -> usually not a direct confirmation, treat as false positive
        return false;
    }


    // Build and send mailto unsubscribe as an email via Gmail API (requires Gmail send scope)
    private boolean sendMailtoUnsubscribe(String mailtoLink) throws Exception {
        String to = mailtoLink.replaceFirst("mailto:", "").trim();
        // remove parameters after ? in mailto if present (e.g. mailto:foo@bar.com?subject=...)
        int q = to.indexOf('?');
        if (q >= 0) to = to.substring(0, q);

        Properties props = new Properties();
        Session session = Session.getInstance(props);

        MimeMessage mime = new MimeMessage(session);
        mime.setFrom(new InternetAddress("me"));
        mime.addRecipient(jakarta.mail.Message.RecipientType.TO, new InternetAddress(to));
        mime.setSubject("unsubscribe");
        mime.setText("Please unsubscribe me from this mailing list.");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        mime.writeTo(buffer);
        byte[] raw = buffer.toByteArray();
        String encoded = Base64.getUrlEncoder().encodeToString(raw);

        com.google.api.services.gmail.model.Message gmailMsg = new com.google.api.services.gmail.model.Message();
        gmailMsg.setRaw(encoded);

        gmail.users().messages().send("me", gmailMsg).execute();
        return true;
    }


    // Extract HTML body from Gmail Message payload, scanning nested parts
    private String getHtmlBodyFromMessage(com.google.api.services.gmail.model.Message message) {
        if (message == null || message.getPayload() == null) return null;

        // BFS through parts
        java.util.Queue<com.google.api.services.gmail.model.MessagePart> q = new java.util.LinkedList<>();
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
                String decoded = new String(Base64.getUrlDecoder().decode(body.getData()));
                if (mimeType != null && mimeType.toLowerCase().contains("html")) {
                    return decoded;
                }
                // sometimes HTML is present in text/plain with embedded HTML; still attempt
                if (decoded.toLowerCase().contains("<html") || decoded.toLowerCase().contains("<a ")) {
                    return decoded;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }


    // Find unsubscribe HTTP link in HTML using Jsoup
    private String findUnsubscribeLinkInHtml(String html) {
        if (html == null) return null;
        try {
            Document doc = Jsoup.parse(html);
            // common heuristics: link text contains "unsubscribe", href contains "unsubscribe", rel="unsub" etc.
            Elements anchors = doc.select("a[href]");
            for (Element a : anchors) {
                String href = a.attr("href");
                String text = a.text();
                String hrefLower = href.toLowerCase();
                String textLower = text == null ? "" : text.toLowerCase();

                if (hrefLower.contains("unsubscribe") || textLower.contains("unsubscribe") || a.hasAttr("rel") && a.attr("rel").toLowerCase().contains("unsub")) {
                    // prefer http(s) href
                    if (hrefLower.startsWith("http://") || hrefLower.startsWith("https://")) {
                        return href;
                    }
                    // sometimes href is mailto:
                    if (hrefLower.startsWith("mailto:")) return href;
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
