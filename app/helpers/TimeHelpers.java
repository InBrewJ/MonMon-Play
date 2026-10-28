package helpers;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class TimeHelpers {
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.of("Europe/London"));

    public static String unixTimestampToDisplayDate(Long timestamp) {
        if (timestamp == null) return "";
        return DISPLAY_FORMATTER.format(Instant.ofEpochSecond(timestamp));
    }

    public static Long generateUnixTimestamp() {
        return Instant.now().getEpochSecond();
    }

    public static String timeAgo(Long timestampSeconds) {
        if (timestampSeconds == null || timestampSeconds <= 0) return "never";
        long diff = Instant.now().getEpochSecond() - timestampSeconds;
        if (diff < 0) diff = 0;
        if (diff < 60) return diff + "s ago";
        if (diff < 3600) return (diff / 60) + "m ago";
        if (diff < 86400) return (diff / 3600) + "h ago";
        if (diff < 604800) return (diff / 86400) + "d ago";
        return (diff / 604800) + "w ago";
    }
}
