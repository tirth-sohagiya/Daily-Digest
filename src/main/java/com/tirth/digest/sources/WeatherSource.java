package com.tirth.digest.sources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tirth.digest.model.Section;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class WeatherSource implements Source {

    private static final String ENDPOINT = "https://api.open-meteo.com/v1/forecast";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("h a");
    private static final DateTimeFormatter HEADING_DATE = DateTimeFormatter.ofPattern("EEE MMM d");

    private static final int RAIN_LIKELY_THRESHOLD_PERCENT = 50;
    private static final int HIGH_UV_INDEX = 8;
    private static final int WINDY_MPH = 25;
    private static final int HOT_FEELS_LIKE_F = 95;
    private static final int COLD_FEELS_LIKE_F = 35;

    private final double latitude;
    private final double longitude;
    private final String timezone;
    private final HttpClient http;

    public WeatherSource(double latitude, double longitude, String timezone) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timezone = timezone;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public String title() {
        return "WEATHER";
    }

    @Override
    public Section fetch() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(buildUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Open-Meteo returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = MAPPER.readTree(response.body());
        JsonNode daily = root.path("daily");

        int high = (int) Math.round(daily.path("temperature_2m_max").get(0).asDouble());
        int low = (int) Math.round(daily.path("temperature_2m_min").get(0).asDouble());
        int rainChance = daily.path("precipitation_probability_max").get(0).asInt();
        int weatherCode = daily.path("weather_code").get(0).asInt();
        int feelsLike = (int) Math.round(daily.path("apparent_temperature_max").get(0).asDouble());
        int uvIndex = (int) Math.round(daily.path("uv_index_max").get(0).asDouble());
        int windSpeed = (int) Math.round(daily.path("wind_speed_10m_max").get(0).asDouble());

        List<String> lines = new ArrayList<>();
        lines.add("%d°F / %d°F, %s".formatted(low, high, describe(weatherCode)));

        String rainStart = firstHourRainLikely(root);
        if (rainStart != null) {
            lines.add("Rain likely from %s (%d%% chance today)".formatted(rainStart, rainChance));
        } else {
            lines.add("%d%% chance of rain".formatted(rainChance));
        }

        if (feelsLike >= HOT_FEELS_LIKE_F) {
            lines.add("Feels like %d°F — stay hydrated".formatted(feelsLike));
        } else if (feelsLike <= COLD_FEELS_LIKE_F) {
            lines.add("Feels like %d°F — bundle up".formatted(feelsLike));
        }

        if (uvIndex >= HIGH_UV_INDEX) {
            lines.add("UV index %d — sunscreen".formatted(uvIndex));
        }

        if (windSpeed >= WINDY_MPH) {
            lines.add("Windy — up to %d mph".formatted(windSpeed));
        }

        return new Section(headingFor(today()), lines);
    }

    private LocalDateTime today() {
        return LocalDateTime.now(ZoneId.of(timezone));
    }

    private String headingFor(LocalDateTime moment) {
        return title() + " — " + moment.format(HEADING_DATE);
    }

    private String buildUrl() {
        return ENDPOINT
                + "?latitude=" + latitude
                + "&longitude=" + longitude
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code"
                + ",apparent_temperature_max,uv_index_max,wind_speed_10m_max"
                + "&hourly=precipitation_probability"
                + "&timezone=" + timezone
                + "&forecast_days=1"
                + "&temperature_unit=fahrenheit"
                + "&wind_speed_unit=mph";
    }

    private String firstHourRainLikely(JsonNode root) {
        JsonNode hourly = root.path("hourly");
        JsonNode times = hourly.path("time");
        JsonNode probabilities = hourly.path("precipitation_probability");
        if (times.isMissingNode() || probabilities.isMissingNode()) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(ZoneId.of(timezone));
        for (int i = 0; i < times.size(); i++) {
            LocalDateTime at = LocalDateTime.parse(times.get(i).asText());
            if (at.isBefore(now)) {
                continue;
            }
            if (probabilities.get(i).asInt() >= RAIN_LIKELY_THRESHOLD_PERCENT) {
                return at.toLocalTime().format(HOUR).toLowerCase();
            }
        }
        return null;
    }

    // WMO weather interpretation codes, per open-meteo.com/en/docs
    private static String describe(int code) {
        return switch (code) {
            case 0 -> "clear";
            case 1 -> "mostly clear";
            case 2 -> "partly cloudy";
            case 3 -> "overcast";
            case 45, 48 -> "foggy";
            case 51, 53, 55 -> "drizzle";
            case 56, 57 -> "freezing drizzle";
            case 61, 63, 65 -> "rain";
            case 66, 67 -> "freezing rain";
            case 71, 73, 75, 77 -> "snow";
            case 80, 81, 82 -> "rain showers";
            case 85, 86 -> "snow showers";
            case 95 -> "thunderstorms";
            case 96, 99 -> "thunderstorms with hail";
            default -> "unknown conditions (code " + code + ")";
        };
    }
}
