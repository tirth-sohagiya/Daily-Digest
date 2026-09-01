package com.tirth.digest.sources;

import com.tirth.digest.Store;
import com.tirth.digest.model.Section;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;

public final class NewsSource implements Source {

    private static final String GOOGLE_NEWS =
            "https://news.google.com/rss/search?hl=en-US&gl=US&ceid=US:en&q=";
    private static final String ECONOMIC_TIMES_NRI =
            "https://economictimes.indiatimes.com/nri/rssfeeds/7771250.cms";

    // Bare "OPT" matches opt-in, opt-out and option, which floods the feed with sports and polling.
    private static final String QUERY =
            "\"STEM OPT\" OR \"optional practical training\" OR \"H-1B\" OR \"F-1 visa\" "
                    + "OR \"international students\" when:3d";

    private static final Pattern RELEVANT = Pattern.compile(
            "h-?1b|h-?4|stem opt|optional practical training|f-1|opt extension"
                    + "|international student|student visa|uscis|sevis|dhs|green card|work authoriz",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern IRRELEVANT = Pattern.compile(
            "opt-out|opt out|opt-in|opt in|free agency|mlb|nba|nfl|opt to |opted to ",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> PREFERRED_SOURCES = List.of(
            "reuters", "bloomberg", "associated press", "ap news", "forbes", "npr",
            "wall street journal", "new york times", "washington post", "cnbc", "politico",
            "axios", "the hill", "inside higher ed", "mercury news", "economic times",
            "times of india", "jurist");

    private static final Set<String> STOPWORDS = Set.of(
            "would", "with", "from", "that", "this", "have", "will", "says", "said", "into",
            "after", "over", "more", "than", "what", "when", "amid", "their", "under", "could",
            "plan", "news", "report", "study", "here", "your", "about", "which", "while");

    private static final int MAX_ITEMS = 4;
    private static final Duration REMEMBER_FOR = Duration.ofDays(60);

    private final Store store;
    private final HttpClient http;

    public NewsSource(Store store) {
        this.store = store;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String title() {
        return "IMMIGRATION NEWS";
    }

    @Override
    public Section fetch() throws Exception {
        List<Headline> candidates = new ArrayList<>();
        candidates.addAll(readFeed(GOOGLE_NEWS + URLEncoder.encode(QUERY, StandardCharsets.UTF_8)));
        candidates.addAll(readFeed(ECONOMIC_TIMES_NRI));

        candidates.sort(Comparator.comparingInt(Headline::rank));

        Set<String> signaturesThisRun = new HashSet<>();
        List<Set<String>> acceptedWords = new ArrayList<>();
        List<String> lines = new ArrayList<>();

        for (Headline headline : candidates) {
            if (lines.size() >= MAX_ITEMS) {
                break;
            }
            if (!signaturesThisRun.add(headline.signature()) || store.hasSeen("NEWS", headline.signature())) {
                continue;
            }

            Set<String> words = headline.significantWords();
            if (acceptedWords.stream().anyMatch(accepted -> retellsSameStory(accepted, words))) {
                continue;
            }
            acceptedWords.add(words);

            lines.add(headline.publisher().isBlank()
                    ? headline.headline()
                    : "%s · %s".formatted(headline.publisher(), headline.headline()));
            store.markSeen("NEWS", headline.signature(), REMEMBER_FOR);
        }

        return new Section(title(), lines);
    }

    private static boolean retellsSameStory(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        long shared = first.stream().filter(second::contains).count();
        return shared * 2 >= Math.min(first.size(), second.size());
    }

    private List<Headline> readFeed(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "daily-digest (github.com/tirth-sohagiya/Daily-Digest)")
                    .GET()
                    .build();

            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return List.of();
            }

            List<Headline> found = new ArrayList<>();
            NodeList items = parse(response.body()).getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Headline headline = Headline.from((Element) items.item(i));
                if (headline != null) {
                    found.add(headline);
                }
            }
            return found;
        } catch (Exception e) {
            // One dead feed must not cost the whole section; the other still contributes.
            return List.of();
        }
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private record Headline(String headline, String publisher, String link, String signature, int rank) {

        static Headline from(Element item) {
            String rawTitle = text(item, "title");
            String link = text(item, "link");
            if (rawTitle.isBlank() || link.isBlank()) {
                return null;
            }

            int split = rawTitle.lastIndexOf(" - ");
            String headline = split > 0 ? rawTitle.substring(0, split) : rawTitle;
            String publisher = split > 0 ? rawTitle.substring(split + 3) : "";

            if (IRRELEVANT.matcher(headline).find() || !RELEVANT.matcher(headline).find()) {
                return null;
            }

            String signature = String.join(" ", headline.toLowerCase()
                    .replaceAll("[^a-z0-9 ]", "")
                    .trim()
                    .split("\\s+"))
                    .lines()
                    .findFirst()
                    .orElse(headline.toLowerCase());
            signature = signature.length() > 60 ? signature.substring(0, 60) : signature;

            return new Headline(headline, publisher, link, signature, rankOf(publisher));
        }

        private static int rankOf(String publisher) {
            String lower = publisher.toLowerCase();
            for (int i = 0; i < PREFERRED_SOURCES.size(); i++) {
                if (Pattern.compile("\\b" + Pattern.quote(PREFERRED_SOURCES.get(i)) + "\\b")
                        .matcher(lower).find()) {
                    return i;
                }
            }
            return PREFERRED_SOURCES.size();
        }

        Set<String> significantWords() {
            return Arrays.stream(headline.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                    .filter(word -> word.length() > 3 && !STOPWORDS.contains(word))
                    .collect(Collectors.toSet());
        }

        private static String text(Element item, String tag) {
            NodeList nodes = item.getElementsByTagName(tag);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        }
    }
}
