package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import models.CalendarEvent;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class GoogleCalendarService {
    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    // 1-hour cache: fromDate_toDate -> cached events
    private final Map<String, List<CalendarEvent>> cache = new ConcurrentHashMap<>();
    private long lastCacheTime = 0;
    private static final long CACHE_TTL_MS = 60 * 60 * 1000; // 1 hour

    @Inject
    public GoogleCalendarService(Config config) {
        this.config = config;
    }

    public boolean isConfigured() {
        boolean hasApi = config.hasPath("gcal.apiKey") && config.hasPath("gcal.calendarId")
                && !config.getString("gcal.apiKey").isBlank() && !config.getString("gcal.calendarId").isBlank();
        boolean hasIcal = config.hasPath("gcal.icalUrl") && !config.getString("gcal.icalUrl").isBlank();
        return hasApi || hasIcal;
    }

    public CompletableFuture<List<CalendarEvent>> getEventsForYear(LocalDate from, LocalDate to) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String cacheKey = from + "_" + to;
        long now = System.currentTimeMillis();
        if (now - lastCacheTime < CACHE_TTL_MS && cache.containsKey(cacheKey)) {
            return CompletableFuture.completedFuture(cache.get(cacheKey));
        }

        if (config.hasPath("gcal.apiKey") && config.hasPath("gcal.calendarId")) {
            String apiKey = config.getString("gcal.apiKey");
            String calendarId = config.getString("gcal.calendarId");
            if (!apiKey.isBlank() && !calendarId.isBlank()) {
                return fetchViaRestApi(apiKey, calendarId, from, to, cacheKey);
            }
        }

        if (config.hasPath("gcal.icalUrl")) {
            String icalUrl = config.getString("gcal.icalUrl");
            if (!icalUrl.isBlank()) {
                return fetchViaIcal(icalUrl, from, to, cacheKey);
            }
        }

        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    private CompletableFuture<List<CalendarEvent>> fetchViaRestApi(String apiKey, String calendarId,
                                                                   LocalDate from, LocalDate to, String cacheKey) {
        String timeMin = from.atStartOfDay(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT);
        String timeMax = to.atTime(23, 59, 59).atZone(ZoneId.of("UTC")).format(DateTimeFormatter.ISO_INSTANT);
        String url = String.format(
                "https://www.googleapis.com/calendar/v3/calendars/%s/events?key=%s&timeMin=%s&timeMax=%s&singleEvents=true&orderBy=startTime",
                calendarId, apiKey, timeMin, timeMax
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        List<CalendarEvent> events = parseRestEvents(response.body());
                        cache.put(cacheKey, events);
                        lastCacheTime = System.currentTimeMillis();
                        return events;
                    }
                    return Collections.<CalendarEvent>emptyList();
                })
                .exceptionally(ex -> {
                    System.err.println("GCal API fetch note (fallback to offline): " + ex.getMessage());
                    return Collections.emptyList();
                });
    }

    private List<CalendarEvent> parseRestEvents(String json) {
        List<CalendarEvent> events = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            if (root.has("items")) {
                for (JsonNode item : root.get("items")) {
                    String id = item.has("id") ? item.get("id").asText() : UUID.randomUUID().toString();
                    String summary = item.has("summary") ? item.get("summary").asText() : "Event";
                    String desc = item.has("description") ? item.get("description").asText() : "";
                    LocalDate eventDate = null;
                    if (item.has("start")) {
                        JsonNode start = item.get("start");
                        if (start.has("date")) {
                            eventDate = LocalDate.parse(start.get("date").asText().substring(0, 10));
                        } else if (start.has("dateTime")) {
                            eventDate = LocalDate.parse(start.get("dateTime").asText().substring(0, 10));
                        }
                    }
                    if (eventDate != null) {
                        events.add(new CalendarEvent(id, summary, eventDate, desc));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing GCal JSON: " + e.getMessage());
        }
        return events;
    }

    private CompletableFuture<List<CalendarEvent>> fetchViaIcal(String icalUrl, LocalDate from, LocalDate to, String cacheKey) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(icalUrl))
                .timeout(Duration.ofSeconds(4))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200) {
                        List<CalendarEvent> events = parseIcalEvents(response.body(), from, to);
                        cache.put(cacheKey, events);
                        lastCacheTime = System.currentTimeMillis();
                        return events;
                    }
                    return Collections.<CalendarEvent>emptyList();
                })
                .exceptionally(ex -> {
                    System.err.println("GCal iCal fetch note: " + ex.getMessage());
                    return Collections.emptyList();
                });
    }

    private List<CalendarEvent> parseIcalEvents(String icalData, LocalDate from, LocalDate to) {
        List<CalendarEvent> events = new ArrayList<>();
        String[] lines = icalData.split("\r?\n");
        String summary = null;
        LocalDate dt = null;
        for (String line : lines) {
            if (line.startsWith("BEGIN:VEVENT")) {
                summary = null;
                dt = null;
            } else if (line.startsWith("SUMMARY:")) {
                summary = line.substring(8).trim();
            } else if (line.startsWith("DTSTART;VALUE=DATE:") || line.startsWith("DTSTART:")) {
                try {
                    String dateStr = line.replaceAll(".*:", "").trim();
                    if (dateStr.length() >= 8) {
                        int y = Integer.parseInt(dateStr.substring(0, 4));
                        int m = Integer.parseInt(dateStr.substring(4, 6));
                        int d = Integer.parseInt(dateStr.substring(6, 8));
                        dt = LocalDate.of(y, m, d);
                    }
                } catch (Exception ignored) {}
            } else if (line.startsWith("END:VEVENT")) {
                if (summary != null && dt != null && !dt.isBefore(from) && !dt.isAfter(to)) {
                    events.add(new CalendarEvent(UUID.randomUUID().toString(), summary, dt, ""));
                }
            }
        }
        return events;
    }
}
