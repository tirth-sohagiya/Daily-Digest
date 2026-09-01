package com.tirth.digest;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.tirth.digest.model.Section;
import com.tirth.digest.sources.AlertSource;
import com.tirth.digest.sources.Source;
import com.tirth.digest.sources.SpendSource;
import com.tirth.digest.sources.WeatherSource;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class Handler implements RequestHandler<Object, String> {

    private static final String DEFAULT_LATITUDE = "37.3382";
    private static final String DEFAULT_LONGITUDE = "-121.8863";
    private static final String DEFAULT_TIMEZONE = "America/Los_Angeles";

    private static final DateTimeFormatter SUBJECT_DATE = DateTimeFormatter.ofPattern("EEE MMM d");

    @Override
    public String handleRequest(Object event, Context context) {
        String timezone = environmentOrDefault("TIMEZONE", DEFAULT_TIMEZONE);
        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        Store store = new Store(requiredEnvironment("TABLE_NAME"));

        if (store.alreadySentOn(today)) {
            context.getLogger().log("Digest for " + today + " already sent; skipping.");
            return "skipped";
        }

        String digest = render(gatherSections(timezone));
        context.getLogger().log(digest);

        Mailer mailer = new Mailer(
                requiredEnvironment("SENDER_EMAIL"),
                requiredEnvironment("RECIPIENT_EMAIL"));

        // The sentinel is written only after SES confirms: a premature write would turn a
        // transient send failure into a silently skipped day.
        String messageId = mailer.send(subjectFor(today), digest);
        store.recordSentOn(today);

        return messageId;
    }

    private static List<Section> gatherSections(String timezone) {
        double latitude = Double.parseDouble(environmentOrDefault("LATITUDE", DEFAULT_LATITUDE));
        double longitude = Double.parseDouble(environmentOrDefault("LONGITUDE", DEFAULT_LONGITUDE));

        List<Source> sources = List.of(
                new WeatherSource(latitude, longitude, timezone),
                new AlertSource(latitude, longitude, timezone),
                new SpendSource()
        );

        return sources.stream()
                .map(Handler::fetchOrPlaceholder)
                .filter(section -> !section.lines().isEmpty())
                .toList();
    }

    private static Section fetchOrPlaceholder(Source source) {
        try {
            return source.fetch();
        } catch (Exception e) {
            // A partial digest beats no digest: one dead source must never stop the rest from rendering.
            return new Section(source.title(), List.of("(unavailable: " + e.getMessage() + ")"));
        }
    }

    private static String subjectFor(LocalDate date) {
        return "Morning — " + date.format(SUBJECT_DATE);
    }

    private static String render(List<Section> sections) {
        StringBuilder out = new StringBuilder();
        for (Section section : sections) {
            out.append(section.title()).append('\n');
            section.lines().forEach(line -> out.append("  ").append(line).append('\n'));
            out.append('\n');
        }
        return out.toString();
    }

    private static String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + name);
        }
        return value;
    }
}
