package com.tirth.digest.sources;

import com.tirth.digest.model.Section;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Datapoint;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.GetMetricStatisticsRequest;
import software.amazon.awssdk.services.cloudwatch.model.Statistic;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public final class SpendSource implements Source {

    // Billing metrics are published only to us-east-1, whatever region the resources live in.
    private static final CloudWatchClient CLOUDWATCH =
            CloudWatchClient.builder().region(Region.US_EAST_1).build();

    private static final Duration PUBLISH_INTERVAL = Duration.ofHours(6);
    private static final Duration LOOKBACK = Duration.ofHours(36);

    @Override
    public String title() {
        return "AWS SPEND";
    }

    @Override
    public Section fetch() {
        GetMetricStatisticsRequest request = GetMetricStatisticsRequest.builder()
                .namespace("AWS/Billing")
                .metricName("EstimatedCharges")
                .dimensions(Dimension.builder().name("Currency").value("USD").build())
                .startTime(Instant.now().minus(LOOKBACK))
                .endTime(Instant.now())
                .period((int) PUBLISH_INTERVAL.toSeconds())
                .statistics(Statistic.MAXIMUM)
                .build();

        Optional<Datapoint> latest = CLOUDWATCH.getMetricStatistics(request).datapoints().stream()
                .max(Comparator.comparing(Datapoint::timestamp));

        // Billing data lags by hours and is absent entirely until billing alerts are enabled;
        // an empty section is dropped by the caller rather than reported as a failure.
        return latest
                .map(point -> Section.of(title(), List.of("$%.2f month to date".formatted(point.maximum()))))
                .orElseGet(() -> Section.of(title(), List.of()));
    }
}
