package com.monisha.sync.ai;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GeminiMetadataExtractorTest {

    @Test
    void testParseGeminiJsonResponse() {
        GeminiMetadataExtractor extractor = new GeminiMetadataExtractor();

        String rawGeminiResponse = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "```json\\n{\\"summary\\": \\"Enterprise cloud synchronization design with Java 21.\\", \\"tags\\": [\\"cloud\\", \\"virtual-threads\\", \\"s3\\", \\"java21\\"]}\\n```"
                      }
                    ]
                  }
                }
              ]
            }
            """;

        DocumentAiMetadata metadata = extractor.parseGeminiResponse(rawGeminiResponse);
        assertNotNull(metadata);
        assertEquals("Enterprise cloud synchronization design with Java 21.", metadata.summary());
        assertEquals(4, metadata.tags().size());
        assertTrue(metadata.tags().contains("cloud"));
        assertTrue(metadata.tags().contains("virtual-threads"));
        assertTrue(metadata.tags().contains("s3"));
        assertTrue(metadata.tags().contains("java21"));
    }

    @Test
    void testExtractTextFromMarkdownAndTxt(@TempDir Path tempDir) throws IOException {
        GeminiMetadataExtractor extractor = new GeminiMetadataExtractor();

        Path mdFile = tempDir.resolve("architecture.md");
        Files.writeString(mdFile, "# Architecture\nThis document details the multi-cloud sync architecture.");

        String extracted = extractor.extractText(mdFile);
        assertTrue(extracted.contains("multi-cloud sync architecture"));
    }

    @Test
    void testHeuristicFallbackWithoutApiKey(@TempDir Path tempDir) throws IOException {
        // Extractor without API key
        GeminiMetadataExtractor extractor = new GeminiMetadataExtractor(
            HttpClient.newHttpClient(), "", "https://example.com"
        );

        Path doc = tempDir.resolve("cloud-sync-overview.txt");
        Files.writeString(doc, "High-performance synchronization engine leveraging Java 21 Virtual Threads and SQLite.");

        DocumentAiMetadata metadata = extractor.extractMetadata(doc);
        assertNotNull(metadata);
        assertFalse(metadata.summary().isBlank());
        assertFalse(metadata.tags().isEmpty());
        assertTrue(metadata.tags().contains("txt"));
    }

    @Test
    void testMockHttpClientCall(@TempDir Path tempDir) throws Exception {
        String geminiJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "{\\"summary\\": \\"Mocked AI document summary\\", \\"tags\\": [\\"mock\\", \\"test\\", \\"ai\\"]}"
                      }
                    ]
                  }
                }
              ]
            }
            """;

        // Create a mock HttpClient using Mockito or dynamic proxy / simple subclass
        HttpResponse<String> mockResponse = new MockHttpResponse(200, geminiJson);
        HttpClient mockHttpClient = new MockHttpClient(mockResponse);

        GeminiMetadataExtractor extractor = new GeminiMetadataExtractor(
            mockHttpClient, "test-api-key", "https://generativelanguage.googleapis.com/v1beta/models/mock:generateContent"
        );

        Path doc = tempDir.resolve("sample.md");
        Files.writeString(doc, "Detailed content for mock testing.");

        DocumentAiMetadata metadata = extractor.extractMetadata(doc);
        assertNotNull(metadata);
        assertEquals("Mocked AI document summary", metadata.summary());
        assertEquals(List.of("mock", "test", "ai"), metadata.tags());
    }

    // Lightweight mock implementations without heavy mocking frameworks
    private static class MockHttpClient extends HttpClient {
        private final HttpResponse<String> response;

        MockHttpClient(HttpResponse<String> response) {
            this.response = response;
        }

        @Override
        public java.util.Optional<java.net.CookieHandler> cookieHandler() { return java.util.Optional.empty(); }
        @Override
        public java.util.Optional<Duration> connectTimeout() { return java.util.Optional.empty(); }
        @Override
        public HttpClient.Redirect followRedirects() { return HttpClient.Redirect.NEVER; }
        @Override
        public java.util.Optional<java.net.ProxySelector> proxy() { return java.util.Optional.empty(); }
        @Override
        public javax.net.ssl.SSLContext sslContext() { return null; }
        @Override
        public javax.net.ssl.SSLParameters sslParameters() { return null; }
        @Override
        public java.util.Optional<java.net.Authenticator> authenticator() { return java.util.Optional.empty(); }
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        @Override
        public java.util.Optional<java.util.concurrent.Executor> executor() { return java.util.Optional.empty(); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return (HttpResponse<T>) response;
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler) {
            return java.util.concurrent.CompletableFuture.completedFuture((HttpResponse<T>) response);
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request, HttpResponse.BodyHandler<T> responseBodyHandler, HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            return java.util.concurrent.CompletableFuture.completedFuture((HttpResponse<T>) response);
        }
    }

    private static class MockHttpResponse implements HttpResponse<String> {
        private final int statusCode;
        private final String body;

        MockHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public int statusCode() { return statusCode; }
        @Override
        public HttpRequest request() { return null; }
        @Override
        public java.util.Optional<HttpResponse<String>> previousResponse() { return java.util.Optional.empty(); }
        @Override
        public java.net.http.HttpHeaders headers() { return java.net.http.HttpHeaders.of(java.util.Map.of(), (k, v) -> true); }
        @Override
        public String body() { return body; }
        @Override
        public java.util.Optional<javax.net.ssl.SSLSession> sslSession() { return java.util.Optional.empty(); }
        @Override
        public URI uri() { return URI.create("https://example.com"); }
        @Override
        public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
    }
}
