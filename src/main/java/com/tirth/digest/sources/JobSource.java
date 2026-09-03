package com.tirth.digest.sources;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tirth.digest.Store;
import com.tirth.digest.model.Section;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JobSource implements Source {

    private static final String LISTINGS =
            "https://raw.githubusercontent.com/SimplifyJobs/New-Grad-Positions/dev/.github/scripts/listings.json";
    private static final String SPONSOR_BOARD =
            "https://raw.githubusercontent.com/zapplyjobs/New-Grad-Jobs-2027/main/README.md";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String WATERMARK = "job-watermark";
    private static final Duration WATERMARK_LIFETIME = Duration.ofDays(365);
    private static final Duration FIRST_RUN_LOOKBACK = Duration.ofHours(24);

    private static final int MAX_SHOWN = 6;

    private static final Set<String> EXCLUDES_ME =
            Set.of("U.S. Citizenship is Required", "Does Not Offer Sponsorship");

    private static final Pattern SPONSOR_ROW =
            Pattern.compile("^\\|\\s*\\*?\\*?\\[?([^|\\[\\]*]+)", Pattern.MULTILINE);

    private final Store store;
    private final List<String> categories;
    private final Pattern locations;
    private final HttpClient http;

    public JobSource(Store store, List<String> categories, List<String> locationNames) {
        this.store = store;
        this.categories = categories;
        this.locations = Pattern.compile(
                locationNames.stream().map(name -> "\\b" + Pattern.quote(name) + "\\b")
                        .reduce((a, b) -> a + "|" + b).orElse("(?!)"),
                Pattern.CASE_INSENSITIVE);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String title() {
        return "NEW GRAD JOBS";
    }

    @Override
    public Section fetch() throws Exception {
        long since = watermark();
        Set<String> sponsors = sponsorCompanies();

        List<Posting> postings = new ArrayList<>();
        long newestSeen = since;

        // The listings file is ~13 MB of mostly historical postings; streaming one element at a
        // time keeps a 512 MB function from holding the whole document in memory.
        try (InputStream body = download(LISTINGS);
             JsonParser parser = MAPPER.getFactory().createParser(body)) {

            parser.nextToken();
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                JsonNode entry = MAPPER.readTree(parser);
                long postedAt = entry.path("date_posted").asLong();
                newestSeen = Math.max(newestSeen, postedAt);

                if (postedAt <= since || !wanted(entry)) {
                    continue;
                }
                postings.add(Posting.from(entry, sponsors));
            }
        }

        store.writeNote(WATERMARK, Long.toString(newestSeen), WATERMARK_LIFETIME);

        postings.sort(Comparator.comparing(Posting::sponsorTagged).reversed()
                .thenComparing(Comparator.comparingLong(Posting::postedAt).reversed()));

        List<String> lines = new ArrayList<>();
        postings.stream().limit(MAX_SHOWN).map(Posting::render).forEach(lines::add);
        if (postings.size() > MAX_SHOWN) {
            lines.add("… %d more".formatted(postings.size() - MAX_SHOWN));
        }

        return new Section("%s — %d new".formatted(title(), postings.size()), lines);
    }

    private boolean wanted(JsonNode entry) {
        if (!entry.path("active").asBoolean() || !entry.path("is_visible").asBoolean()) {
            return false;
        }
        if (EXCLUDES_ME.contains(entry.path("sponsorship").asText(""))) {
            return false;
        }
        if (!categories.contains(entry.path("category").asText(""))) {
            return false;
        }
        for (JsonNode location : entry.path("locations")) {
            if (locations.matcher(location.asText()).find()) {
                return true;
            }
        }
        return false;
    }

    private long watermark() {
        String stored = store.readNote(WATERMARK);
        if (stored.isBlank()) {
            return Instant.now().minus(FIRST_RUN_LOOKBACK).getEpochSecond();
        }
        return Long.parseLong(stored.trim());
    }

    private Set<String> sponsorCompanies() {
        Set<String> tagged = new HashSet<>();
        try (InputStream body = download(SPONSOR_BOARD)) {
            String markdown = new String(body.readAllBytes());
            for (String row : markdown.split("\n")) {
                if (!row.startsWith("|") || (!row.contains("🏛") && !row.contains("✅"))) {
                    continue;
                }
                Matcher matcher = SPONSOR_ROW.matcher(row);
                if (matcher.find()) {
                    tagged.add(normalize(matcher.group(1)));
                }
            }
        } catch (Exception e) {
            // The sponsor tag is a bonus; losing it must not cost the listings themselves.
            return Set.of();
        }
        return tagged;
    }

    private InputStream download(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "daily-digest (github.com/tirth-sohagiya/Daily-Digest)")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream()).body();
    }

    private static String normalize(String company) {
        return company.toLowerCase()
                .replaceAll("\\b(inc|llc|corp|corporation|ltd|limited|co|the)\\b", "")
                .replaceAll("[^a-z0-9]", "")
                .trim();
    }

    private record Posting(String company, String title, String location, long postedAt,
                           boolean sponsorTagged) {

        static Posting from(JsonNode entry, Set<String> sponsors) {
            String company = entry.path("company_name").asText("Unknown");
            JsonNode locations = entry.path("locations");
            return new Posting(
                    company,
                    entry.path("title").asText("Role"),
                    locations.isEmpty() ? "" : locations.get(0).asText(),
                    entry.path("date_posted").asLong(),
                    sponsors.contains(normalize(company)));
        }

        String render() {
            String marker = sponsorTagged ? "H-1B · " : "";
            return location.isBlank()
                    ? "%s%s — %s".formatted(marker, company, title)
                    : "%s%s — %s — %s".formatted(marker, company, title, location);
        }
    }
}
