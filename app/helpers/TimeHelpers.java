package helpers;

import java.time.Instant;

public class TimeHelpers {
    public static Long generateUnixTimestamp() {
        return Instant.now().getEpochSecond();
    }
}
