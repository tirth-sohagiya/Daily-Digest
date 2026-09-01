package com.tirth.digest;

import com.tirth.digest.model.Section;
import com.tirth.digest.sources.AlertSource;
import com.tirth.digest.sources.QuoteSource;
import com.tirth.digest.sources.Source;
import com.tirth.digest.sources.SpendSource;
import com.tirth.digest.sources.WeatherSource;

import java.util.List;

public final class Main {

    private static final double SAN_JOSE_LATITUDE = 37.3382;
    private static final double SAN_JOSE_LONGITUDE = -121.8863;
    private static final String SAN_JOSE_TIMEZONE = "America/Los_Angeles";

    public static void main(String[] args) {
        List<Source> sources = List.of(
                new WeatherSource(SAN_JOSE_LATITUDE, SAN_JOSE_LONGITUDE, SAN_JOSE_TIMEZONE),
                new AlertSource(SAN_JOSE_LATITUDE, SAN_JOSE_LONGITUDE, SAN_JOSE_TIMEZONE),
                new SpendSource(),
                new QuoteSource(SAN_JOSE_TIMEZONE)
        );

        for (Source source : sources) {
            try {
                Section section = source.fetch();
                if (section.lines().isEmpty()) {
                    continue;
                }
                System.out.println(section.title());
                section.lines().forEach(line -> System.out.println("  " + line));
            } catch (Exception e) {
                // A partial digest beats no digest: one dead source must never stop the rest from rendering.
                System.out.println(source.title());
                System.out.println("  (unavailable: " + e.getMessage() + ")");
            }
            System.out.println();
        }
    }
}
