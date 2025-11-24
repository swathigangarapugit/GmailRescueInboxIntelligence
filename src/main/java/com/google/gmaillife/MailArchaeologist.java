package com.google.gmaillife;

import com.google.adk.tools.Annotations.Schema;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.ListMessagesResponse;

import java.util.LinkedHashMap;
import java.util.Map;

public class MailArchaeologist {

    private final Gmail gmail;

    public MailArchaeologist(Gmail gmail) {
        this.gmail = gmail;
    }

    @Schema(
            name = "scanMailbox",
            description = "Scan the user's inbox and return counts by Gmail category"
    )
    public Map<String, Object> scanMailbox() throws Exception {

        Map<String, Object> result = new LinkedHashMap<>();

        Map<String, String> categories = Map.of(
                "promotions", "category:promotions",
                "social", "category:social",
                "updates", "category:updates",
                "forums", "category:forums",
                "primary", "-category:{promotions social updates forums}"
        );

        Map<String, Long> counts = new LinkedHashMap<>();

        for (var entry : categories.entrySet()) {
            ListMessagesResponse response = gmail.users().messages()
                    .list("me")
                    .setQ(entry.getValue())
                    .setMaxResults(500L)
                    .execute();

            long count = response.getResultSizeEstimate() != null
                    ? response.getResultSizeEstimate()
                    : (response.getMessages() == null ? 0 : response.getMessages().size());

            counts.put(entry.getKey(), count);
        }

        result.put("summary", counts);
        result.put("status", "scan-complete");

        return result;
    }
}
