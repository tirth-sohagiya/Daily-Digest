package com.tirth.digest.sources;

import com.tirth.digest.model.Section;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public final class OptStatusSource implements Source {

    private static final DateTimeFormatter RUNS_OUT = DateTimeFormatter.ofPattern("MMM d");
    private static final long WARN_BELOW_DAYS = 30;

    private final String optStartDate;
    private final String employmentStartDate;
    private final long allowanceDays;
    private final String timezone;

    public OptStatusSource(String optStartDate, String employmentStartDate,
                           long allowanceDays, String timezone) {
        this.optStartDate = optStartDate;
        this.employmentStartDate = employmentStartDate;
        this.allowanceDays = allowanceDays;
        this.timezone = timezone;
    }

    @Override
    public String title() {
        return "OPT STATUS";
    }

    @Override
    public Section fetch() {
        if (optStartDate == null || optStartDate.isBlank()) {
            return new Section(title(), List.of());
        }

        LocalDate today = LocalDate.now(ZoneId.of(timezone));
        LocalDate optStart = LocalDate.parse(optStartDate);

        // The allowance counts days actually spent unemployed, so employment freezes the tally
        // rather than ending it — an unemployment gap later draws on the same remaining balance.
        LocalDate countUntil = employed() ? LocalDate.parse(employmentStartDate) : today;
        long used = Math.max(0, ChronoUnit.DAYS.between(optStart, countUntil));
        long remaining = allowanceDays - used;

        List<String> lines = new ArrayList<>();
        if (employed()) {
            lines.add("%d of %d unemployment days used · clock stopped %s"
                    .formatted(used, allowanceDays, LocalDate.parse(employmentStartDate).format(RUNS_OUT)));
            return new Section(title(), lines);
        }

        if (remaining <= 0) {
            lines.add("Unemployment allowance exhausted as of %s"
                    .formatted(optStart.plusDays(allowanceDays).format(RUNS_OUT)));
        } else {
            lines.add("%d of %d unemployment days remaining · through %s"
                    .formatted(remaining, allowanceDays, optStart.plusDays(allowanceDays).format(RUNS_OUT)));
            if (remaining <= WARN_BELOW_DAYS) {
                lines.add("Under %d days left — confirm your options with your DSO".formatted(WARN_BELOW_DAYS));
            }
        }
        return new Section(title(), lines);
    }

    private boolean employed() {
        return employmentStartDate != null && !employmentStartDate.isBlank();
    }
}
