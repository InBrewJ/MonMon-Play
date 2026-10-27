package helpers;

import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

public class BuildInfo {
    private static final Properties props = new Properties();
    private static boolean loaded = false;

    private static synchronized void loadProps() {
        if (loaded) {
            return;
        }
        loaded = true;
        try (InputStream is = BuildInfo.class.getClassLoader().getResourceAsStream("build.properties")) {
            if (is != null) {
                props.load(is);
            }
        } catch (Exception e) {
            System.err.println("Failed to load build.properties: " + e.getMessage());
        }
    }

    public static String getBuildNumber() {
        loadProps();
        String num = props.getProperty("build.number");
        if (num != null && !num.trim().isEmpty()) {
            return num.trim();
        }
        return "dev";
    }

    public static String getBuildTimestamp() {
        loadProps();
        String ts = props.getProperty("build.timestamp");
        if (ts != null && !ts.trim().isEmpty()) {
            return ts.trim();
        }
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " (dev)";
    }

    public static String getBuildCommit() {
        loadProps();
        String commit = props.getProperty("build.commit");
        if (commit != null && !commit.trim().isEmpty()) {
            return commit.trim();
        }
        return "local";
    }
}
