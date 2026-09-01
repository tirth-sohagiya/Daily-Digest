package com.tirth.digest.sources;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tirth.digest.model.Section;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DeadlineSource implements Source {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String deadlinesJson;
    private final String timezone;

    public DeadlineSource(String deadlinesJson, String timezone) {
        this.deadlinesJson = deadlinesJson;
        this.timezone = timezone;
    }

    @Override
    public String title() {
        return "DEADLINES";
    }

    @Override
    public Section fetch() throws Exception {
        if (deadlinesJson == null || deadlinesJson.isBlank()) {
            return new Section(title(), List.of());
        }

        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        List<String> lines = new ArrayList<>(MAPPER
                .readValue(deadlinesJson, new TypeReference<Map<String, String>>() {})
                .entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), LocalDate.parse(entry.getValue())))
                .filter(entry -> !entry.getValue().isBefore(today))
                .sorted(Map.Entry.comparingByValue())
                .map(entry -> describe(entry.getKey(), ChronoUnit.DAYS.between(today, entry.getValue())))
                .toList());

        return new Section(title(), lines);
    }

    private static String describe(String label, long daysAway) {
        return switch ((int) Math.min(daysAway, 2)) {
            case 0 -> "today · " + label;
            case 1 -> "tomorrow · " + label;
            default -> daysAway + " days · " + label;
        };
    }
}
