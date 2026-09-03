package com.tirth.digest.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tirth.digest.Store;
import com.tirth.digest.model.Line;
import com.tirth.digest.model.Section;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ImmigrationSource implements Source {

    private static final String ENDPOINT = "https://www.federalregister.gov/api/v1/documents.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> SEARCH_TERMS = List.of(
            "nonimmigrant student", "student visa", "H-1B",
            "optional practical training", "SEVP");
    private static final int LOOKBACK_DAYS = 30;
    private static final Duration REMEMBER_FOR = Duration.ofDays(365);

    // Full-text search matches any passing mention, so long unrelated rules surface constantly.
    // Requiring the subject in the title is what separates a fee change from a Medicaid notice.
    private static final Pattern RELEVANT_TITLE = Pattern.compile(
            "h-?1b|optional practical training|stem opt|f-1|m-1|j-1|sevp|sevis|student visa"
                    + "|nonimmigrant|exchange visitor|employment authorization|duration of status"
                    + "|international student|immigra|foreign worker|alien",
            Pattern.CASE_INSENSITIVE);

    private final Store store;
    private final String timezone;
    private final HttpClient http;

    public ImmigrationSource(Store store, String timezone) {
        this.store = store;
        this.timezone = timezone;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String title() {
        return "FEDERAL REGISTER";
    }

    @Override
    public Section fetch() throws Exception {
        List<Line> lines = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();

        for (String term : SEARCH_TERMS) {
            for (JsonNode document : search(term)) {
                String documentNumber = document.path("document_number").asText();
                String documentTitle = document.path("title").asText();

                if (documentNumber.isBlank() || !RELEVANT_TITLE.matcher(documentTitle).find()) {
                    continue;
                }
                if (!reported.add(documentNumber) || store.hasSeen("FEDREG", documentNumber)) {
                    continue;
                }

                lines.add(new Line(
                        "%s · %s".formatted(document.path("type").asText("Document"), documentTitle),
                        document.path("html_url").asText()));
                store.markSeen("FEDREG", documentNumber, REMEMBER_FOR);
            }
        }

        return new Section(title(), lines);
    }

    private JsonNode search(String term) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(buildUrl(term)))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Federal Register returned HTTP " + response.statusCode());
        }
        return MAPPER.readTree(response.body()).path("results");
    }

    private String buildUrl(String term) {
        LocalDate since = LocalDate.now(ZoneId.of(timezone)).minusDays(LOOKBACK_DAYS);
        return ENDPOINT
                + "?per_page=20&order=newest"
                + "&conditions[term]=" + URLEncoder.encode(term, StandardCharsets.UTF_8)
                + "&conditions[publication_date][gte]=" + since;
    }
}
