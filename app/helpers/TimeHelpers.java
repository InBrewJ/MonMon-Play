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
}
