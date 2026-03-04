package com.flywaysafety.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flywaysafety.model.MigrationFile;
import com.flywaysafety.model.SafetyRating;
import com.flywaysafety.model.SchemaContext;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class LLMAnalyzer {

    private static final String CLAUDE_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL = "claude-opus-4-6";
    private static final MediaType JSON_MEDIA = MediaType.get("application/json; charset=utf-8");

    private static final String SYSTEM_PROMPT = """
            You are a PostgreSQL database migration safety expert. Your job is to evaluate Flyway SQL migration scripts \
            for production safety risks.

            Evaluate the migration for:
            1. Lock risk: Does it acquire long-duration locks (AccessExclusiveLock)? Will it block queries?
            2. Data loss: Does it irreversibly delete or modify data?
            3. Performance impact: Could it cause long-running scans or rewrites on large tables?
            4. Constraint violations: Could it fail if existing data violates new constraints?
            5. Reversibility: Can this migration be safely rolled back?

            Respond with ONLY valid JSON (no markdown fences) in this exact format:
            {"rating": "RED|AMBER|GREEN", "risks": ["risk1", "risk2"], "explanation": "concise explanation"}

            Rating guidelines:
            - RED: Imminent production risk — data loss, multi-minute locks, or guaranteed failures on large tables
            - AMBER: Moderate risk — requires review or careful timing; may be safe with precautions
            - GREEN: Low risk — safe for production deployment without special precautions
            """;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public LLMAnalyzer(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    public AnalysisResult analyze(MigrationFile migration, SchemaContext schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            return AnalysisResult.of(SafetyRating.AMBER, List.of("LLM_SKIPPED: No API key provided"), "LLM analysis skipped.");
        }

        try {
            String userMessage = buildUserMessage(migration, schemaContext);
            String responseText = callClaude(userMessage);
            return parseResponse(responseText);
        } catch (Exception e) {
            System.err.println("[LLMAnalyzer] Error calling Claude API: " + e.getMessage());
            return AnalysisResult.of(SafetyRating.AMBER,
                    List.of("LLM_ERROR: " + e.getMessage()),
                    "LLM analysis failed; defaulting to AMBER.");
        }
    }

    private String buildUserMessage(MigrationFile migration, SchemaContext schemaContext) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Migration File: ").append(migration.filename()).append("\n\n");
        sb.append("### SQL:\n```sql\n").append(migration.sql()).append("\n```\n\n");
        sb.append("### ").append(schemaContext.toContextString(migration.sql()));
        return sb.toString();
    }

    private String callClaude(String userMessage) throws IOException {
        String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<String, Object>() {{
            put("model", MODEL);
            put("max_tokens", 1024);
            put("system", SYSTEM_PROMPT);
            put("messages", List.of(
                    new java.util.LinkedHashMap<String, Object>() {{
                        put("role", "user");
                        put("content", userMessage);
                    }}
            ));
        }});

        Request request = new Request.Builder()
                .url(CLAUDE_API_URL)
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(requestBody, JSON_MEDIA))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "(no body)";
                throw new IOException("Claude API returned HTTP " + response.code() + ": " + errorBody);
            }
            String body = response.body().string();
            JsonNode root = objectMapper.readTree(body);
            return root.path("content").get(0).path("text").asText();
        }
    }

    private AnalysisResult parseResponse(String text) {
        // Strip markdown code fences if present
        String json = extractJson(text);

        try {
            JsonNode node = objectMapper.readTree(json);
            String ratingStr = node.path("rating").asText("AMBER").toUpperCase();
            SafetyRating rating;
            try {
                rating = SafetyRating.valueOf(ratingStr);
            } catch (IllegalArgumentException e) {
                rating = SafetyRating.AMBER;
            }

            List<String> risks = new ArrayList<>();
            JsonNode risksNode = node.path("risks");
            if (risksNode.isArray()) {
                for (JsonNode r : risksNode) {
                    risks.add(r.asText());
                }
            }

            String explanation = node.path("explanation").asText("");
            return AnalysisResult.of(rating, risks, explanation);

        } catch (Exception e) {
            System.err.println("[LLMAnalyzer] Failed to parse JSON response: " + e.getMessage());
            return AnalysisResult.of(SafetyRating.AMBER,
                    List.of("LLM_PARSE_ERROR: " + e.getMessage()),
                    "Could not parse LLM response; defaulting to AMBER. Raw: " + text.substring(0, Math.min(200, text.length())));
        }
    }

    private String extractJson(String text) {
        // Remove markdown fences
        text = text.strip();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.lastIndexOf("```")).strip();
            }
        }

        // Extract the outermost JSON object
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
