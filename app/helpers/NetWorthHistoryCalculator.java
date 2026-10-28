package helpers;

import models.Account;
import models.Balance;
import models.NetWorthAsset;
import play.libs.Json;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Calculates historical time-series trajectory points for Net Worth:
 * 1. Historic and current value of all pensions
 * 2. Historic and current values of all cash and savings accounts on one line
 * 3. Historic and current value of all fixed assets (with sold/deleted assets dropping to 0 from deletion onwards)
 */
public class NetWorthHistoryCalculator {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK);

    public static class HistoryPoint {
        private final long timestamp;
        private final String dateStr;
        private final double pensions;
        private final double cashSavings;
        private final double fixedAssets;
        private final double total;

        public HistoryPoint(long timestamp, String dateStr, double pensions, double cashSavings, double fixedAssets, double total) {
            this.timestamp = timestamp;
            this.dateStr = dateStr;
            this.pensions = pensions;
            this.cashSavings = cashSavings;
            this.fixedAssets = fixedAssets;
            this.total = total;
        }

        public long getTimestamp() { return timestamp; }
        public String getDateStr() { return dateStr; }
        public double getPensions() { return pensions; }
        public double getCashSavings() { return cashSavings; }
        public double getFixedAssets() { return fixedAssets; }
        public double getTotal() { return total; }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new HashMap<>();
            m.put("timestamp", timestamp);
            m.put("dateStr", dateStr);
            m.put("pensions", pensions);
            m.put("cashSavings", cashSavings);
            m.put("fixedAssets", fixedAssets);
            m.put("total", total);
            return m;
        }
    }

    public static List<HistoryPoint> calculate(
            List<Account> accounts,
            List<Balance> directBalances,
            List<NetWorthAsset> allAssets,
            LocalDate today,
            ZoneId zone
    ) {
        if (today == null) {
            today = LocalDate.now(zone != null ? zone : ZoneId.of("Europe/London"));
        }
        if (zone == null) {
            zone = ZoneId.of("Europe/London");
        }

        long nowSec = today.atTime(23, 59, 59).atZone(zone).toEpochSecond();
        LocalDate tenYearsAgo = today.minusYears(10).withDayOfMonth(1);
        long tenYearsAgoSec = tenYearsAgo.atStartOfDay(zone).toEpochSecond();

        // 1. Group accounts by category
        List<Account> safeAccounts = accounts != null ? accounts : Collections.emptyList();
        List<Account> pensionAccounts = safeAccounts.stream()
                .filter(a -> a != null && a.getType() == Account.AccountType.PENSION)
                .collect(Collectors.toList());

        List<Account> cashSavingsAccounts = safeAccounts.stream()
                .filter(a -> a != null && (
                        a.getType() == Account.AccountType.DEBIT
                                || a.getType() == Account.AccountType.SHORT_TERM_SAVINGS
                                || a.getType() == Account.AccountType.LONG_TERM_SAVINGS
                                || a.getType() == Account.AccountType.DEBIT_SHARED_BILLS
                ))
                .collect(Collectors.toList());

        // 2. Build map of account balances sorted chronologically
        Map<Long, List<Balance>> accountBalancesMap = new HashMap<>();

        // Collect from accounts eager collection
        for (Account a : safeAccounts) {
            if (a.getId() != null && a.balances != null) {
                List<Balance> sorted = a.balances.stream()
                        .filter(b -> b != null && b.getTimestamp() != null && b.getValue() != null)
                        .sorted(Comparator.comparing(Balance::getTimestamp).thenComparing(b -> b.getId() != null ? b.getId() : 0L))
                        .collect(Collectors.toList());
                accountBalancesMap.put(a.getId(), new ArrayList<>(sorted));
            }
        }

        // Merge with directBalances if provided
        if (directBalances != null) {
            for (Balance b : directBalances) {
                if (b != null && b.getAccount() != null && b.getAccount().getId() != null && b.getTimestamp() != null && b.getValue() != null) {
                    Long accId = b.getAccount().getId();
                    accountBalancesMap.computeIfAbsent(accId, k -> new ArrayList<>()).add(b);
                }
            }
            // Re-sort
            for (List<Balance> list : accountBalancesMap.values()) {
                list.sort(Comparator.comparing(Balance::getTimestamp).thenComparing(b -> b.getId() != null ? b.getId() : 0L));
            }
        }

        // 3. Process fixed assets
        List<NetWorthAsset> safeAssets = allAssets != null ? allAssets : Collections.emptyList();

        // 4. Collect sampling timestamps across last 10 years
        TreeSet<Long> timestampSet = new TreeSet<>();

        // Monthly anchors
        LocalDate curMonth = tenYearsAgo;
        while (!curMonth.isAfter(today)) {
            timestampSet.add(curMonth.atStartOfDay(zone).toEpochSecond());
            curMonth = curMonth.plusMonths(1);
        }

        // Balance timestamps
        for (List<Balance> bList : accountBalancesMap.values()) {
            for (Balance b : bList) {
                if (b.getTimestamp() >= tenYearsAgoSec && b.getTimestamp() <= nowSec) {
                    timestampSet.add(b.getTimestamp());
                }
            }
        }

        // Fixed asset event timestamps (creation and archiving)
        for (NetWorthAsset asset : safeAssets) {
            if (asset.getCreatedAt() != null) {
                long cSec = asset.getCreatedAt() / 1000L;
                if (cSec >= tenYearsAgoSec && cSec <= nowSec) {
                    timestampSet.add(cSec);
                }
            }
            if (asset.isArchived() && asset.getUpdatedAt() != null) {
                long uSec = asset.getUpdatedAt() / 1000L;
                if (uSec >= tenYearsAgoSec && uSec <= nowSec) {
                    timestampSet.add(uSec);
                }
            }
        }

        timestampSet.add(nowSec);

        // 5. Coalesce timestamps that fall on the exact same date to keep timeline lean
        Map<String, Long> dateToLatestTimestamp = new LinkedHashMap<>();
        for (Long t : timestampSet) {
            LocalDate d = Instant.ofEpochSecond(t).atZone(zone).toLocalDate();
            dateToLatestTimestamp.put(d.toString(), t);
        }

        List<Long> coalescedTimestamps = new ArrayList<>(dateToLatestTimestamp.values());
        Collections.sort(coalescedTimestamps);

        // 6. Compute snapshot values at each timestamp
        List<HistoryPoint> points = new ArrayList<>();
        for (Long t : coalescedTimestamps) {
            double pensions = calculateAccountsValueAt(pensionAccounts, accountBalancesMap, t, true);
            double cashSavings = calculateAccountsValueAt(cashSavingsAccounts, accountBalancesMap, t, false);
            double fixedAssets = calculateFixedAssetsValueAt(safeAssets, t);
            double total = round2(pensions + cashSavings + fixedAssets);

            LocalDate d = Instant.ofEpochSecond(t).atZone(zone).toLocalDate();
            String dateStr = DATE_FORMATTER.format(d);

            points.add(new HistoryPoint(t, dateStr, pensions, cashSavings, fixedAssets, total));
        }

        return points;
    }

    private static double calculateAccountsValueAt(
            List<Account> accounts,
            Map<Long, List<Balance>> accountBalancesMap,
            long timestampSec,
            boolean clampZero
    ) {
        double sum = 0.0;
        for (Account a : accounts) {
            if (a.getId() == null) continue;
            List<Balance> balances = accountBalancesMap.get(a.getId());
            if (balances == null || balances.isEmpty()) continue;

            Balance latest = null;
            for (Balance b : balances) {
                if (b.getTimestamp() <= timestampSec) {
                    latest = b;
                } else {
                    break;
                }
            }

            if (latest != null && latest.getValue() != null) {
                double val = latest.getValue();
                if (clampZero && val < 0) val = 0.0;
                sum += val;
            }
        }
        return round2(sum);
    }

    private static double calculateFixedAssetsValueAt(List<NetWorthAsset> assets, long timestampSec) {
        double sum = 0.0;
        for (NetWorthAsset asset : assets) {
            if (asset == null) continue;
            long startSec = asset.getCreatedAt() != null ? (asset.getCreatedAt() / 1000L) : 0L;
            long endSec = (asset.isArchived() && asset.getUpdatedAt() != null) ? (asset.getUpdatedAt() / 1000L) : Long.MAX_VALUE;

            if (timestampSec >= startSec && timestampSec < endSec) {
                double val = asset.getValue() != null ? asset.getValue() : 0.0;
                sum += Math.max(0.0, val);
            }
            // If timestampSec >= endSec, asset was deleted / sold, so its value is 0!
        }
        return round2(sum);
    }

    public static String buildChartHistoryJson(List<HistoryPoint> points) {
        if (points == null || points.isEmpty()) {
            return "[]";
        }
        List<Map<String, Object>> list = points.stream()
                .map(HistoryPoint::toMap)
                .collect(Collectors.toList());
        return Json.stringify(Json.toJson(list));
    }

    private static double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
