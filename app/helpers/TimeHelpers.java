package helpers;

import java.time.Instant;
import java.util.Date;

public class TimeHelpers {
    public static String unixTimestampToDisplayDate(Long timestamp) {
        Date d =  new Date(timestamp);
        return d.toString();
    }
    public static Long generateUnixTimestamp() {
        return Instant.now().getEpochSecond();
    }
}
