package com.tirth.digest.sources;

import com.tirth.digest.model.Section;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

public final class QuoteSource implements Source {

    private static final String RESOURCE = "/quotes.txt";
    private static final List<String> QUOTES = load();

    private final String timezone;

    public QuoteSource(String timezone) {
        this.timezone = timezone;
    }

    @Override
    public String title() {
        return "—";
    }

    @Override
    public Section fetch() {
        if (QUOTES.isEmpty()) {
            return new Section(title(), List.of());
        }

        int dayOfYear = LocalDate.now(ZoneId.of(timezone)).getDayOfYear();
        String[] parts = QUOTES.get(dayOfYear % QUOTES.size()).split("\\|", 2);

        return new Section(title(), parts.length == 2
                ? List.of('"' + parts[0] + '"', "— " + parts[1])
                : List.of(parts[0]));
    }

    private static List<String> load() {
        try (InputStream stream = QuoteSource.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return List.of();
            }
            try (BufferedReader reader =
                         new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                return reader.lines().filter(line -> !line.isBlank()).toList();
            }
        } catch (Exception e) {
            return List.of();
        }
    }
}
