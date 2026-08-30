package helpers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

public class BankHolidayHelper {
    public static final ZoneId UK_ZONE = ZoneId.of("Europe/London");
    private static final Set<LocalDate> UK_BANK_HOLIDAYS = new HashSet<>();

    static {
        loadBankHolidays();
    }

    private static void loadBankHolidays() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = null;
            // 1. Try classpath resource
            InputStream is = BankHolidayHelper.class.getClassLoader().getResourceAsStream("bank-holidays-uk.json");
            if (is != null) {
                root = mapper.readTree(is);
            } else {
                // 2. Try conf/ file
                File confFile = new File("conf/bank-holidays-uk.json");
                if (confFile.exists()) {
                    root = mapper.readTree(confFile);
                }
            }

            if (root != null && root.has("events")) {
                for (JsonNode eventNode : root.get("events")) {
                    if (eventNode.has("date")) {
                        UK_BANK_HOLIDAYS.add(LocalDate.parse(eventNode.get("date").asText()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Warning: could not load bank-holidays-uk.json: " + e.getMessage());
        }

        // Hardcoded safety net for crucial bank holidays (e.g. 2026-08-31)
        UK_BANK_HOLIDAYS.add(LocalDate.of(2026, 8, 31)); // Summer Bank Holiday 2026
        UK_BANK_HOLIDAYS.add(LocalDate.of(2026, 12, 25));
        UK_BANK_HOLIDAYS.add(LocalDate.of(2026, 12, 28));
    }

    public static ZoneId getTimezone(String region) {
        return UK_ZONE;
    }

    public static LocalDate nowInRegion(String region) {
        return LocalDate.now(getTimezone(region));
    }

    public static boolean isBankHoliday(LocalDate date) {
        return UK_BANK_HOLIDAYS.contains(date);
    }

    public static boolean isWeekend(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
    }

    public static boolean isWorkingDay(LocalDate date) {
        return !isWeekend(date) && !isBankHoliday(date);
    }

    public static LocalDate getLastWorkingDayOnOrBefore(LocalDate date) {
        LocalDate current = date;
        while (!isWorkingDay(current)) {
            current = current.minusDays(1);
        }
        return current;
    }
}
