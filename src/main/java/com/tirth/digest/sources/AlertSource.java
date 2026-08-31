package com.tirth.digest.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tirth.digest.model.Section;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class AlertSource implements Source {

    private static final String ENDPOINT = "https://api.weather.gov/alerts/active";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter ENDS_AT = DateTimeFormatter.ofPattern("h:mm a EEE");

    // The National Weather Service rejects requests that do not identify the caller.
    private static final String USER_AGENT = "daily-digest (github.com/tirth-sohagiya/Daily-Digest)";

    private final double latitude;
    private final double longitude;
    private final String timezone;
    private final HttpClient http;

    public AlertSource(double latitude, double longitude, String timezone) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timezone = timezone;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String title() {
        return "ALERTS";
    }

    @Override
    public Section fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(buildUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/geo+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "National Weather Service returned HTTP " + response.statusCode());
        }

        List<String> lines = new ArrayList<>();
        for (JsonNode feature : MAPPER.readTree(response.body()).path("features")) {
            lines.add(describe(feature.path("properties")));
        }

        return new Section(title(), lines);
    }

    private String buildUrl() {
        return ENDPOINT + "?point=" + latitude + "," + longitude;
    }

    private String describe(JsonNode properties) {
        String event = properties.path("event").asText("Weather alert");
        String until = endsAt(properties);
        return until == null ? event : event + " until " + until;
    }

    private String endsAt(JsonNode properties) {
        for (String field : List.of("ends", "expires")) {
            if (!properties.hasNonNull(field)) {
                continue;
            }
            String value = properties.get(field).asText();
            if (!value.isBlank()) {
                return OffsetDateTime.parse(value)
                        .atZoneSameInstant(ZoneId.of(timezone))
                        .format(ENDS_AT);
            }
        }
        return null;
    }
}
