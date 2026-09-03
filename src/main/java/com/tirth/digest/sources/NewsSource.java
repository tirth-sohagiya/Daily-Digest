package com.tirth.digest.sources;

import com.tirth.digest.Store;
import com.tirth.digest.model.Line;
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
            "opt-out|opt out|opt-in|opt in|free agency|mlb|nba|nfl|opt to |opted to "
                    + "|\\bcanada\\b|\\bcanadian\\b|\\baustralia\\b|\\bireland\\b|\\bnew zealand\\b|\\bschengen\\b",
            Pattern.CASE_INSENSITIVE);

    private static final List<String> PREFERRED_SOURCES = List.of(
            "reuters", "bloomberg", "associated press", "ap news", "forbes", "npr",
            "wall street journal", "new york times", "washington post", "cnbc", "politico",
            "axios", "the hill", "inside higher ed", "mercury news", "economic times",
            "times of india", "jurist");

    // Compiled once at class load rather than per headline; the word boundaries stop an outlet
    // name matching inside a longer word, which is how goodmenproject.com once matched "npr".
    private static final List<Pattern> PREFERRED_SOURCE_PATTERNS = PREFERRED_SOURCES.stream()
            .map(name -> Pattern.compile("\\b" + Pattern.quote(name) + "\\b", Pattern.CASE_INSENSITIVE))
            .toList();

    private static final Set<String> STOPWORDS = Set.of(
            "would", "with", "from", "that", "this", "have", "will", "says", "said", "into",
            "after", "over", "more", "than", "what", "when", "amid", "their", "under", "could",
            "plan", "news", "report", "study", "here", "your", "about", "which", "while");

    // Policy reporting and personal anecdotes come from the same outlets, so outlet alone cannot
    // separate them. Serious coverage can be written plainly, so the tabloid markers below do more
    // work than the policy vocabulary does.
    private static final Pattern POLICY_SIGNAL = Pattern.compile(
            "\\b(rule|proposal|proposed|policy|fee|regulation|court|judge|lawsuit|sued|bill"
                    + "|congress|senate|dhs|uscis|sevis|federal|administration|ban|deadline|petition"
                    + "|cap|lottery|extension|eligibility|guidance|permits?|authorization|restrict\\w*"
                    + "|limits?|revoke\\w*|terminat\\w*|eliminat\\w*|end(s|ing)?)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern TABLOID_SIGNAL = Pattern.compile(
            "[\"\u201C\u201D].{6,}[\"\u201C\u201D]"
                    // A single quote only counts when it opens a phrase, so possessives
                    // like Trump's and Holders' are not mistaken for quoted speech.
                    + "|(^|\\s)['\u2018][^'\u2019]{6,}['\u2019](\\s|:|,|\\.|$)"
                    + "|\\b\\d{1,2}-year-old\\b"
                    + "|\\b(says|said|claims?|claimed|alleges|alleged|explains|shares|reveals"
                    + "|slams|blasts|viral|netizens|reddit|redditor|sparks|opinion)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final int TABLOID_PENALTY = 2;

    private static final int TRUNCATION_SUSPECTED_AT = 95;

    private static final int MAX_ITEMS = 4;
    private static final int MINIMUM_SCORE = 0;
    private static final int REMEMBERED_HEADLINES = 40;
    private static final String RECENT_HEADLINES = "recent-headlines";
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
        candidates.addAll(readFeed(GOOGLE_NEWS + URLEncoder.encode(QUERY, StandardCharsets.UTF_8), ""));
        candidates.addAll(readFeed(ECONOMIC_TIMES_NRI, "The Economic Times"));

        candidates.sort(Comparator.comparingInt(Headline::rank)
                .thenComparing(Comparator.comparingInt(Headline::policyScore).reversed()));

        // An exact-signature check misses the same story reworded by another outlet the next day,
        // so recent headlines are compared by word overlap exactly as same-run duplicates are.
        List<String> remembered = new ArrayList<>(List.of(store.readNote(RECENT_HEADLINES).split("\n")));
        remembered.removeIf(String::isBlank);

        List<Set<String>> alreadyTold = remembered.stream().map(NewsSource::significantWords).toList();
        List<Set<String>> acceptedWords = new ArrayList<>(alreadyTold);
        Set<String> signaturesThisRun = new HashSet<>();
        List<String> chosen = new ArrayList<>();
        List<Line> lines = new ArrayList<>();

        for (Headline headline : candidates) {
            if (lines.size() >= MAX_ITEMS) {
                break;
            }
            if (headline.policyScore() < MINIMUM_SCORE) {
                continue;
            }
            if (!signaturesThisRun.add(headline.signature()) || store.hasSeen("NEWS", headline.signature())) {
                continue;
            }

            Set<String> words = significantWords(headline.headline());
            if (acceptedWords.stream().anyMatch(told -> retellsSameStory(told, words))) {
                continue;
            }
            acceptedWords.add(words);
            chosen.add(headline.headline());

            lines.add(new Line(headline.publisher().isBlank()
                    ? headline.headline()
                    : "%s · %s".formatted(headline.publisher(), headline.headline()),
                    headline.link()));
            store.markSeen("NEWS", headline.signature(), REMEMBER_FOR);
        }

        if (!chosen.isEmpty()) {
            remembered.addAll(chosen);
            List<String> trimmed = remembered.size() > REMEMBERED_HEADLINES
                    ? remembered.subList(remembered.size() - REMEMBERED_HEADLINES, remembered.size())
                    : remembered;
            store.writeNote(RECENT_HEADLINES, String.join("\n", trimmed), REMEMBER_FOR);
        }

        return new Section(title(), lines);
    }

    private static Set<String> significantWords(String headline) {
        return Arrays.stream(headline.toLowerCase().replaceAll("[^a-z0-9 ]", " ").split("\\s+"))
                .filter(word -> word.length() > 3 && !STOPWORDS.contains(word))
                .collect(Collectors.toSet());
    }

    private static boolean retellsSameStory(Set<String> first, Set<String> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        long shared = first.stream().filter(second::contains).count();
        return shared * 2 >= Math.min(first.size(), second.size());
    }

    /**
     * Google News appends " - Publisher" to every title; a publication's own feed does not,
     * so the caller supplies the name rather than leaving those items unattributed and unranked.
     */
    private List<Headline> readFeed(String url, String knownPublisher) {
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
                Headline headline = Headline.from((Element) items.item(i), knownPublisher);
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

    private record Headline(String headline, String publisher, String link, String signature,
                            int rank, int policyScore) {

        static Headline from(Element item, String knownPublisher) {
            String rawTitle = text(item, "title");
            String link = text(item, "link");
            if (rawTitle.isBlank() || link.isBlank()) {
                return null;
            }

            int split = knownPublisher.isBlank() ? rawTitle.lastIndexOf(" - ") : -1;
            String headline = split > 0 ? rawTitle.substring(0, split) : rawTitle;
            String publisher = split > 0 ? rawTitle.substring(split + 3) : knownPublisher;

            if (IRRELEVANT.matcher(headline).find() || !RELEVANT.matcher(headline).find()) {
                return null;
            }

            String normalized = String.join(" ", headline.toLowerCase()
                    .replaceAll("[^a-z0-9 ]", "")
                    .trim()
                    .split("\\s+"));
            String signature = normalized.length() > 60 ? normalized.substring(0, 60) : normalized;

            return new Headline(trimToWholeWord(headline), publisher, link, signature,
                    rankOf(publisher), policySignalCount(headline));
        }

        /**
         * Google News truncates titles near 103 characters, leaving fragments like "behind q" or
         * "CEO cla". Length is the reliable signal: a short final word is only suspicious on a
         * headline long enough to have been cut, since plenty of intact headlines end in "fee".
         */
        private static String trimToWholeWord(String headline) {
            if (headline.length() < TRUNCATION_SUSPECTED_AT
                    || !headline.matches(".*\\s+\\S{1,4}$")) {
                return headline;
            }
            return headline.substring(0, headline.lastIndexOf(' ')).replaceAll("[,;:]$", "") + "…";
        }

        private static int policySignalCount(String headline) {
            return matchCount(POLICY_SIGNAL, headline) - TABLOID_PENALTY * matchCount(TABLOID_SIGNAL, headline);
        }

        private static int matchCount(Pattern pattern, String text) {
            java.util.regex.Matcher matcher = pattern.matcher(text);
            int count = 0;
            while (matcher.find()) {
                count++;
            }
            return count;
        }

        private static int rankOf(String publisher) {
            for (int i = 0; i < PREFERRED_SOURCE_PATTERNS.size(); i++) {
                if (PREFERRED_SOURCE_PATTERNS.get(i).matcher(publisher).find()) {
                    return i;
                }
            }
            return PREFERRED_SOURCE_PATTERNS.size();
        }

        private static String text(Element item, String tag) {
            NodeList nodes = item.getElementsByTagName(tag);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        }
    }
}
