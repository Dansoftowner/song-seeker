package com.songseeker;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

/// Responsible for communicating with ChatGPT API.
public class AIService {

    public static final String DEFAULT_MODEL = "gpt-5.4";

    private final BooleanProperty supported = new SimpleBooleanProperty(false);

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private String apiKey;

    public AIService() {
        this.client = HttpClient.newHttpClient();
        this.apiKey = System.getenv("OPENAI_API_KEY");
        supported.set(this.apiKey != null && !this.apiKey.isBlank());
    }

    private String getInstructions() {
        return """
                You are a song search assistant.
                The user may write in Hungarian or English.
                The user may describe a song they vaguely remember, or ask for recommendations based on mood, style, era, lyrics, instruments, or context.
                Return only valid JSON.
                If you are unsure, still give the best possible matches or recommendations and explain the uncertainty briefly in the reasoning field.
                Output format:
                {
                  "songs": [
                    {
                      "title": "Song title",
                      "author": "Artist or band",
                      "genre": "Primary genre",
                      "link": "https://...",
                      "reasoning": "Short reason why this song matches the request"
                    }
                  ]
                }
                Rules:
                - Return between 1 and 8 songs.
                - Prefer official or well-known links when possible.
                - Every song must contain non-empty title, author, genre, and link fields.
                - Keep reasoning short.
                """;
    }

    public record SongResult(String title, String author, String genre, String link, String reasoning) {
    }

    /// Sends the prompt to ChatGPT and returns song results.
    ///
    /// @param model the model to use
    /// @param input the user input
    public List<SongResult> searchSongs(String model, String input) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("API key not set");
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException("Search query is empty");
        }

        ObjectNode request = mapper.createObjectNode();

        request.put("model", model);
        request.put("instructions", getInstructions());
        request.put("input", """
                Search for songs based on this user request and answer in JSON:
                %s
                """.formatted(input.trim()));

        ObjectNode textNode = mapper.createObjectNode();
        textNode.set("format", mapper.createObjectNode().put("type", "json_object"));
        request.set("text", textNode);

        String jsonRequest = mapper.writeValueAsString(request);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create("https://api.openai.com/v1/responses"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonRequest, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> httpResponse = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (httpResponse.statusCode() < 200 || httpResponse.statusCode() >= 300) {
            throw new IOException("OpenAI request failed (%d): %s".formatted(
                    httpResponse.statusCode(),
                    extractErrorMessage(httpResponse.body())
            ));
        }

        JsonNode root = mapper.readTree(httpResponse.body());
        JsonNode refusal = root.path("refusal");
        if (!refusal.isMissingNode() && !refusal.isNull() && !refusal.asText("").isBlank()) {
            throw new IOException("The model refused the request: " + refusal.asText());
        }

        String output = extractOutputText(root);
        if (output == null || output.isBlank()) {
            throw new IOException("The model returned an empty response.");
        }

        JsonNode jsonObject = mapper.readTree(output);
        if (!jsonObject.isObject()) {
            throw new IOException("The model did not return a JSON object.");
        }

        JsonNode songsNode = jsonObject.path("songs");
        if (!songsNode.isArray()) {
            throw new IOException("The response does not contain a songs array.");
        }

        List<SongResult> results = new ArrayList<>();
        for (JsonNode songNode : songsNode) {
            SongResult result = parseSong(songNode);
            if (result != null) {
                results.add(result);
            }
        }

        if (results.isEmpty()) {
            throw new IOException("No songs were returned.");
        }

        return results;
    }

    /// Backward-compatible wrapper.
    public List<String> sendPrompt(String model, String input) throws IOException, InterruptedException {
        List<String> output = new LinkedList<>();
        for (SongResult result : searchSongs(model, input)) {
            output.add("%s - %s".formatted(result.title(), result.author()));
        }
        return output;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = Objects.requireNonNullElse(apiKey, "").trim();
        supported.set(!this.apiKey.isBlank());
    }

    public BooleanProperty supportedProperty() {
        return supported;
    }

    private SongResult parseSong(JsonNode songNode) {
        if (!songNode.isObject()) {
            return null;
        }

        String title = songNode.path("title").asText("").trim();
        String author = songNode.path("author").asText("").trim();
        String genre = songNode.path("genre").asText("").trim();
        String link = songNode.path("link").asText("").trim();
        String reasoning = songNode.path("reasoning").asText("").trim();

        if (title.isEmpty() || author.isEmpty() || genre.isEmpty() || link.isEmpty()) {
            return null;
        }

        return new SongResult(title, author, genre, link, reasoning);
    }

    private String extractOutputText(JsonNode root) {
        String topLevelOutputText = root.path("output_text").asText("");
        if (!topLevelOutputText.isBlank()) {
            return topLevelOutputText;
        }

        JsonNode outputArray = root.path("output");
        if (!outputArray.isArray()) {
            return "";
        }

        StringBuilder builder = new StringBuilder();
        for (JsonNode outputItem : outputArray) {
            if (!"message".equals(outputItem.path("type").asText())) {
                continue;
            }

            JsonNode contentItems = outputItem.path("content");
            if (!(contentItems instanceof ArrayNode)) {
                continue;
            }

            for (JsonNode contentItem : contentItems) {
                if ("output_text".equals(contentItem.path("type").asText())) {
                    builder.append(contentItem.path("text").asText());
                }
            }
        }

        return builder.toString();
    }

    private String extractErrorMessage(String responseBody) {
        try {
            JsonNode root = mapper.readTree(responseBody);
            JsonNode errorMessage = root.path("error").path("message");
            if (!errorMessage.asText("").isBlank()) {
                return errorMessage.asText();
            }
        } catch (Exception ignored) {
        }

        return responseBody;
    }
}
