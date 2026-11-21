package com.monisha.sync.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts document text chunks and requests structured summaries and keyword tags
 * from the Google Gemini REST API using Java 21 HttpClient.
 */
public class GeminiMetadataExtractor {

    private static final Logger log = LoggerFactory.getLogger(GeminiMetadataExtractor.class);
    private static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent";
    private static final int MAX_EXTRACT_CHARS = 8000;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String endpointUrl;

    public GeminiMetadataExtractor() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(),
             System.getenv("GEMINI_API_KEY"),
             DEFAULT_ENDPOINT);
    }

    public GeminiMetadataExtractor(HttpClient httpClient, String apiKey, String endpointUrl) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient must not be null");
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.endpointUrl = endpointUrl != null ? endpointUrl : DEFAULT_ENDPOINT;
    }

    /**
     * Extracts text from supported file types (.md, .txt, .pdf) and generates AI metadata.
     *
     * @param filePath path to the local file
     * @return DocumentAiMetadata containing summary and tags
     */
    public DocumentAiMetadata extractMetadata(Path filePath) {
        Objects.requireNonNull(filePath, "filePath must not be null");

        String textContent = extractText(filePath);
        if (textContent.isBlank()) {
            return DocumentAiMetadata.empty();
        }

        // If no API key is configured, supply intelligent heuristic metadata
        if (apiKey.isBlank()) {
            log.info("GEMINI_API_KEY not configured: using heuristic metadata for [{}]", filePath.getFileName());
            return generateHeuristicMetadata(filePath, textContent);
        }

        try {
            return requestGeminiMetadata(textContent);
        } catch (Exception e) {
            log.warn("Gemini API call failed for [{}], falling back to heuristics: {}", filePath.getFileName(), e.getMessage());
            return generateHeuristicMetadata(filePath, textContent);
        }
    }

    /**
     * Extracts readable text from .txt, .md, and .pdf files.
     */
    public String extractText(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        try {
            if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
                String full = Files.readString(filePath, StandardCharsets.UTF_8);
                return full.length() > MAX_EXTRACT_CHARS ? full.substring(0, MAX_EXTRACT_CHARS) : full;
            } else if (fileName.endsWith(".pdf")) {
                return extractPdfText(filePath);
            }
        } catch (Exception e) {
            log.debug("Could not read text from [{}]: {}", filePath, e.getMessage());
        }
        return "";
    }

    /**
     * Lightweight PDF stream text extractor for common plain and encoded PDF streams.
     */
    private String extractPdfText(Path pdfPath) throws IOException {
        byte[] bytes = Files.readAllBytes(pdfPath);
        String raw = new String(bytes, StandardCharsets.ISO_8859_1);
        StringBuilder sb = new StringBuilder();

        // Match PDF text strings: (Text here) Tj or [(T) (e) (x) (t)] TJ
        Pattern tjPattern = Pattern.compile("\\(([^()]*)\\)\\s*Tj");
        Matcher matcher = tjPattern.matcher(raw);
        while (matcher.find() && sb.length() < MAX_EXTRACT_CHARS) {
            String text = matcher.group(1).trim();
            if (!text.isEmpty()) {
                sb.append(text).append(" ");
            }
        }

        if (sb.isEmpty()) {
            // Fallback: extract any printable ASCII sequences of length >= 4
            Pattern asciiPattern = Pattern.compile("[A-Za-z0-9 ,.?!:;'-]{4,}");
            Matcher am = asciiPattern.matcher(raw);
            while (am.find() && sb.length() < MAX_EXTRACT_CHARS) {
                sb.append(am.group().trim()).append(" ");
            }
        }

        return sb.toString().trim();
    }

    private DocumentAiMetadata requestGeminiMetadata(String documentText) throws Exception {
        String prompt = """
            Analyze the following document and output ONLY a JSON object with:
            1. "summary": A concise 1-2 sentence overview of the document.
            2. "tags": A JSON list of 3 to 7 relevant topic keyword tags.
            Output JSON only, no markdown or markdown backticks.

            Document:
            """ + documentText;

        String escapedPrompt = escapeJson(prompt);
        String requestBody = String.format("""
            {
              "contents": [{
                "parts": [{"text": "%s"}]
              }],
              "generationConfig": {
                "responseMimeType": "application/json"
              }
            }
            """, escapedPrompt);

        URI targetUri = URI.create(endpointUrl + (endpointUrl.contains("?") ? "&" : "?") + "key=" + apiKey);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(targetUri)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(15))
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Gemini API returned status " + response.statusCode() + ": " + response.body());
        }

        return parseGeminiResponse(response.body());
    }

    @SuppressWarnings("unchecked")
    public DocumentAiMetadata parseGeminiResponse(String responseBody) {
        Yaml yaml = new Yaml();
        Map<String, Object> root = yaml.load(responseBody);

        if (root == null || !root.containsKey("candidates")) {
            return DocumentAiMetadata.empty();
        }

        List<Map<String, Object>> candidates = (List<Map<String, Object>>) root.get("candidates");
        if (candidates == null || candidates.isEmpty()) {
            return DocumentAiMetadata.empty();
        }

        Map<String, Object> candidate = candidates.get(0);
        Map<String, Object> content = (Map<String, Object>) candidate.get("content");
        if (content == null || !content.containsKey("parts")) {
            return DocumentAiMetadata.empty();
        }

        List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
        if (parts == null || parts.isEmpty()) {
            return DocumentAiMetadata.empty();
        }

        String rawJsonText = (String) parts.get(0).get("text");
        if (rawJsonText == null || rawJsonText.isBlank()) {
            return DocumentAiMetadata.empty();
        }

        // Clean any code block fencing
        String cleanedJson = rawJsonText
            .replaceAll("(?s)^```(?:json)?\\s*", "")
            .replaceAll("(?s)```\\s*$", "")
            .trim();

        Map<String, Object> parsed = yaml.load(cleanedJson);
        if (parsed == null) {
            return DocumentAiMetadata.empty();
        }

        String summary = (String) parsed.getOrDefault("summary", "");
        List<String> tags = new ArrayList<>();
        Object tagsObj = parsed.get("tags");
        if (tagsObj instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    tags.add(item.toString().toLowerCase().trim());
                }
            }
        }

        return new DocumentAiMetadata(summary, tags);
    }

    private DocumentAiMetadata generateHeuristicMetadata(Path filePath, String text) {
        String fileName = filePath.getFileName().toString();
        List<String> tags = new ArrayList<>();

        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            tags.add(fileName.substring(dot + 1).toLowerCase());
        }

        // Extract potential keywords from filename and text
        String[] words = (fileName + " " + text.substring(0, Math.min(200, text.length()))).split("[^A-Za-z0-9_-]+");
        for (String w : words) {
            String lower = w.toLowerCase().trim();
            if (lower.length() > 4 && !tags.contains(lower) && tags.size() < 6) {
                tags.add(lower);
            }
        }

        String summary = text.length() > 150 ? text.substring(0, 147).replaceAll("\\s+", " ").trim() + "..." : text.trim();
        return new DocumentAiMetadata(summary, tags);
    }

    private String escapeJson(String raw) {
        return raw.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\b", "\\b")
                  .replace("\f", "\\f")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }
}
