package weather;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Weather service exposing NWS (api.weather.gov) data as MCP tools.
 * See: https://modelcontextprotocol.io/docs/develop/build-server#java
 */
@Service
public class WeatherService {

    private static final String BASE_URL = "https://api.weather.gov";

    private final RestClient restClient;

    public WeatherService() {
        this.restClient = RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader("Accept", "application/geo+json")
                .defaultHeader("User-Agent", "weather-app/1.0 (Java MCP)")
                .build();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Points(@JsonProperty("properties") Props properties) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Props(@JsonProperty("forecast") String forecast) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Forecast(@JsonProperty("properties") Props properties) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Props(@JsonProperty("periods") List<Period> periods) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Period(
            @JsonProperty("number") Integer number,
            @JsonProperty("name") String name,
            @JsonProperty("startTime") String startTime,
            @JsonProperty("endTime") String endTime,
            @JsonProperty("isDaytime") Boolean isDayTime,
            @JsonProperty("temperature") Integer temperature,
            @JsonProperty("temperatureUnit") String temperatureUnit,
            @JsonProperty("temperatureTrend") String temperatureTrend,
            @JsonProperty("probabilityOfPrecipitation") Map<String, Object> probabilityOfPrecipitation,
            @JsonProperty("windSpeed") String windSpeed,
            @JsonProperty("windDirection") String windDirection,
            @JsonProperty("icon") String icon,
            @JsonProperty("shortForecast") String shortForecast,
            @JsonProperty("detailedForecast") String detailedForecast) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alert(@JsonProperty("features") List<Feature> features) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Feature(@JsonProperty("properties") Properties properties) {}
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record Properties(
                @JsonProperty("event") String event,
                @JsonProperty("areaDesc") String areaDesc,
                @JsonProperty("severity") String severity,
                @JsonProperty("description") String description,
                @JsonProperty("instruction") String instruction) {}
    }

    /**
     * Get weather forecast for a specific latitude/longitude (US only, NWS API).
     */
    @Tool(description = "Get weather forecast for a specific latitude/longitude. Returns temperature, wind, and forecast text.")
    public String getWeatherForecastByLocation(
            @ToolParam(description = "Latitude of the location") double latitude,
            @ToolParam(description = "Longitude of the location") double longitude) {
        try {
            Points points = restClient.get()
                    .uri("/points/{latitude},{longitude}", latitude, longitude)
                    .retrieve()
                    .body(Points.class);
            if (points == null || points.properties() == null || points.properties().forecast() == null) {
                return "Failed to get forecast URL for this location. NWS supports US locations only.";
            }
            Forecast forecast = restClient.get()
                    .uri(points.properties().forecast())
                    .retrieve()
                    .body(Forecast.class);
            if (forecast == null || forecast.properties() == null || forecast.properties().periods() == null) {
                return "Failed to retrieve forecast data.";
            }
            return forecast.properties().periods().stream()
                    .map(p -> String.format("""
                            %s:
                            Temperature: %s°%s
                            Wind: %s %s
                            Forecast: %s
                            ---
                            """,
                            nullToEmpty(p.name()),
                            p.temperature() != null ? p.temperature() : "?",
                            nullToEmpty(p.temperatureUnit()),
                            nullToEmpty(p.windSpeed()),
                            nullToEmpty(p.windDirection()),
                            nullToEmpty(p.detailedForecast())))
                    .collect(Collectors.joining());
        } catch (RestClientException e) {
            return "Failed to retrieve forecast: " + e.getMessage() + ". This location may not be supported (US only).";
        }
    }

    /**
     * Get weather alerts for a US state (two-letter code).
     */
    @Tool(description = "Get weather alerts for a US state. Input is two-letter US state code (e.g. CA, NY).")
    public String getAlerts(
            @ToolParam(description = "Two-letter US state code (e.g. CA, NY)") String state) {
        if (state == null || state.isBlank()) {
            return "State code is required (e.g. CA, NY).";
        }
        String stateCode = state.trim().toUpperCase();
        if (stateCode.length() != 2) {
            return "Use a two-letter state code (e.g. CA, NY).";
        }
        try {
            Alert alert = restClient.get()
                    .uri("/alerts/active/area/{state}", stateCode)
                    .retrieve()
                    .body(Alert.class);
            if (alert == null || alert.features() == null || alert.features().isEmpty()) {
                return "No active alerts for " + stateCode + ".";
            }
            return alert.features().stream()
                    .map(f -> {
                        Alert.Properties props = f.properties();
                        if (props == null) return "Unknown alert\n---";
                        return String.format("""
                                Event: %s
                                Area: %s
                                Severity: %s
                                Description: %s
                                Instructions: %s
                                ---
                                """,
                                nullToEmpty(props.event()),
                                nullToEmpty(props.areaDesc()),
                                nullToEmpty(props.severity()),
                                nullToEmpty(props.description()),
                                nullToEmpty(props.instruction()));
                    })
                    .collect(Collectors.joining());
        } catch (RestClientException e) {
            return "Failed to retrieve alerts: " + e.getMessage();
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
