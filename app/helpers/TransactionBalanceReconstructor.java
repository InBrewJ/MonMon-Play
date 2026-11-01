package helpers;

import models.Balance;
import models.BankTransaction;
import models.PendingStatement;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mathematical reconstruction engine for transaction lists vs. bank statements.
 *
 * Theory:
 * A transaction list represents a discrete sequence of deltas:
 *   Δᵢ = inflowᵢ - outflowᵢ
 *
 * Having exactly one anchor balance B(t₀) anywhere within (or at the boundary of)
 * that contiguous sequence allows determining the balance at every other point in time:
 *
 *   • Forward reconstruction:  B(tₖ)  = B(t₀) + ∑ Δᵢ  (for t₀ < tᵢ ≤ tₖ)
 *   • Backward reconstruction: B(t₋ₖ) = B(t₀) - ∑ Δᵢ  (for t₋ₖ < tᵢ ≤ t₀)
 */
public class TransactionBalanceReconstructor {

    /** Morning UTC hour (09:00) for inflows/salary credits so payday peaks land at start of day. */
    public static final int INFLOW_HOUR_UTC = 9;
    /** Afternoon UTC hour (17:00) for outflows/debits after morning inflows have cleared. */
    public static final int OUTFLOW_HOUR_UTC = 17;

    public static class ReconstructionResult {
        private final float openingBalance;
        private final float closingBalance;
        private final float netDelta;
        private final long anchorTimestamp;
        private final float anchorBalance;
        private final Map<Long, Float> runningBalances;
        private final boolean reconstructed;

        public ReconstructionResult(float openingBalance, float closingBalance, float netDelta,
                                    long anchorTimestamp, float anchorBalance,
                                    Map<Long, Float> runningBalances, boolean reconstructed) {
            this.openingBalance = openingBalance;
            this.closingBalance = closingBalance;
            this.netDelta = netDelta;
            this.anchorTimestamp = anchorTimestamp;
            this.anchorBalance = anchorBalance;
            this.runningBalances = runningBalances;
            this.reconstructed = reconstructed;
        }

        public float getOpeningBalance() {
            return openingBalance;
        }

        public float getClosingBalance() {
            return closingBalance;
        }

        public float getNetDelta() {
            return netDelta;
        }

        public long getAnchorTimestamp() {
            return anchorTimestamp;
        }

        public float getAnchorBalance() {
            return anchorBalance;
        }

        public Map<Long, Float> getRunningBalances() {
            return runningBalances;
        }

        public boolean isReconstructed() {
            return reconstructed;
        }
    }

    public static class TransactionDelta {
        private final long timestamp;
        private final float delta; // Positive for inflow (credit/paid in), negative for outflow (debit/paid out)
        private final String description;

        public TransactionDelta(long timestamp, float delta, String description) {
            this.timestamp = timestamp;
            this.delta = delta;
            this.description = description;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public float getDelta() {
            return delta;
        }

        public String getDescription() {
            return description;
        }
    }

    /**
     * Determines whether a statement is a pure transaction list (export without balances)
     * rather than an official statement with verified opening/closing balances.
     */
    public static boolean isTransactionListOnly(PendingStatement statement) {
        if (statement == null) return false;
        // If bank name or payload indicates transaction export
        String bank = statement.getBankName() != null ? statement.getBankName().toLowerCase() : "";
        if (bank.contains("transaction") || bank.contains("txn")) {
            return true;
        }
        // If opening and closing balances are zero or missing, but transactions exist
        boolean zeroBalances = (statement.getOpeningBalance() == null || Math.abs(statement.getOpeningBalance()) < 0.001f)
                && (statement.getClosingBalance() == null || Math.abs(statement.getClosingBalance()) < 0.001f);
        return zeroBalances && statement.getTransactionCount() != null && statement.getTransactionCount() > 0;
    }

    /**
     * Convert BankTransaction objects into TransactionDeltas.
     * Note on MonMon convention:
     * In BankTransaction, an expense (spend) is amount > 0, which corresponds to delta < 0.
     * An income / deposit is amount < 0, which corresponds to delta > 0.
     */
    public static List<TransactionDelta> fromBankTransactions(List<BankTransaction> transactions) {
        List<TransactionDelta> deltas = new ArrayList<>();
        if (transactions == null) return deltas;

        for (BankTransaction tx : transactions) {
            long ts = tx.getTimestamp() != null ? tx.getTimestamp() : 0L;
            // Spend in MonMon is positive amount -> delta is -amount. Income is negative amount -> delta is +|amount|.
            float delta = -tx.getAmount();
            deltas.add(new TransactionDelta(ts, delta, tx.getDescription()));
        }

        return normalizeIntradayDeltas(deltas);
    }

    /**
     * Ensures that when multiple transactions share the same timestamp (such as statement rows
     * that only carry a calendar date YYYY-MM-DD), inflows (credits/salary, delta > 0) are
     * ordered first at 01:00:00 UTC + i (so the post-inflow peak is captured at the start of the day)
     * and outflows (debits/transfers, delta <= 0) are ordered after at 17:00:00 UTC + j.
     * If all timestamps are already distinct, their original timestamps are preserved.
     */
    public static List<TransactionDelta> normalizeIntradayDeltas(List<TransactionDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) return Collections.emptyList();

        Set<Long> seenTimestamps = new HashSet<>();
        boolean hasCollision = false;
        for (TransactionDelta d : deltas) {
            if (!seenTimestamps.add(d.getTimestamp())) {
                hasCollision = true;
                break;
            }
        }

        if (!hasCollision) {
            List<TransactionDelta> sorted = new ArrayList<>(deltas);
            sorted.sort(Comparator.comparingLong(TransactionDelta::getTimestamp));
            return sorted;
        }

        // Check if colliding timestamps represent real calendar dates (>= 1970-01-02)
        boolean calendarDates = deltas.stream().allMatch(d -> d.getTimestamp() >= 86400L);
        if (calendarDates) {
            Map<LocalDate, List<TransactionDelta>> byDate = new TreeMap<>();
            for (TransactionDelta d : deltas) {
                LocalDate ld = Instant.ofEpochSecond(d.getTimestamp()).atZone(ZoneOffset.UTC).toLocalDate();
                byDate.computeIfAbsent(ld, k -> new ArrayList<>()).add(d);
            }

            List<TransactionDelta> normalized = new ArrayList<>(deltas.size());
            for (Map.Entry<LocalDate, List<TransactionDelta>> entry : byDate.entrySet()) {
                LocalDate ld = entry.getKey();
                List<TransactionDelta> dayDeltas = entry.getValue();
                int inflowIdx = 0;
                int outflowIdx = 0;
                // Process inflows (delta > 0) first at 01:00:00 UTC + i
                for (TransactionDelta d : dayDeltas) {
                    if (d.getDelta() > 0) {
                        long ts = ld.atTime(INFLOW_HOUR_UTC, 0, 0).toEpochSecond(ZoneOffset.UTC) + (inflowIdx++);
                        normalized.add(new TransactionDelta(ts, d.getDelta(), d.getDescription()));
                    }
                }
                // Process outflows (delta <= 0) next at 17:00:00 UTC + j
                for (TransactionDelta d : dayDeltas) {
                    if (d.getDelta() <= 0) {
                        long ts = ld.atTime(OUTFLOW_HOUR_UTC, 0, 0).toEpochSecond(ZoneOffset.UTC) + (outflowIdx++);
                        normalized.add(new TransactionDelta(ts, d.getDelta(), d.getDescription()));
                    }
                }
            }
            normalized.sort(Comparator.comparingLong(TransactionDelta::getTimestamp));
            return normalized;
        } else {
            // Synthetic test timestamps with collision: order inflows before outflows and offset by +1s
            List<TransactionDelta> sorted = new ArrayList<>(deltas);
            sorted.sort(Comparator.comparingLong(TransactionDelta::getTimestamp)
                    .thenComparingInt(d -> d.getDelta() > 0 ? 0 : 1));
            List<TransactionDelta> deduped = new ArrayList<>(sorted.size());
            long lastTs = Long.MIN_VALUE;
            for (TransactionDelta d : sorted) {
                long ts = d.getTimestamp();
                if (ts <= lastTs) {
                    ts = lastTs + 1L;
                }
                lastTs = ts;
                deduped.add(new TransactionDelta(ts, d.getDelta(), d.getDescription()));
            }
            return deduped;
        }
    }

    /**
     * Reconstructs opening and closing balance given a list of deltas and an anchor balance at t0.
     *
     * @param deltas Chronological list of transaction deltas
     * @param anchorTimestamp Timestamp of known balance B(t0)
     * @param anchorBalance Value of known balance B(t0)
     * @param startTimestamp Range start
     * @param endTimestamp Range end
     */
    public static ReconstructionResult reconstruct(
            List<TransactionDelta> deltas,
            long anchorTimestamp,
            float anchorBalance,
            long startTimestamp,
            long endTimestamp) {

        if (deltas == null || deltas.isEmpty()) {
            return new ReconstructionResult(anchorBalance, anchorBalance, 0.0f,
                    anchorTimestamp, anchorBalance, Collections.emptyMap(), true);
        }

        // Normalize & sort deltas chronologically (inflows at 01:00 UTC before outflows at 17:00 UTC)
        List<TransactionDelta> sorted = normalizeIntradayDeltas(deltas);

        // If anchorTimestamp is a real calendar date at noon (12:00 UTC) or evening (>= 17:00 UTC),
        // it represents that day's end-of-day balance, so include all 17:00:xx outflows on that day.
        long effectiveAnchorTs = anchorTimestamp;
        if (anchorTimestamp >= 86400L) {
            java.time.ZonedDateTime anchorZdt = Instant.ofEpochSecond(anchorTimestamp).atZone(ZoneOffset.UTC);
            int hr = anchorZdt.getHour();
            if (hr == 12 || hr >= OUTFLOW_HOUR_UTC) {
                effectiveAnchorTs = anchorZdt.toLocalDate().atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC);
            }
        }

        float netDelta = 0.0f;
        for (TransactionDelta d : sorted) {
            netDelta += d.getDelta();
        }

        // 1. Backward reconstruction from anchorTimestamp to startTimestamp:
        // B(start) = B(anchor) - sum(deltas where start < t <= anchor)
        float backwardSum = 0.0f;
        for (TransactionDelta d : sorted) {
            if (d.getTimestamp() <= effectiveAnchorTs && d.getTimestamp() >= startTimestamp) {
                backwardSum += d.getDelta();
            }
        }
        float calculatedOpening = anchorBalance - backwardSum;

        // 2. Forward reconstruction from anchorTimestamp to endTimestamp:
        // B(end) = B(anchor) + sum(deltas where anchor < t <= end)
        float forwardSum = 0.0f;
        for (TransactionDelta d : sorted) {
            if (d.getTimestamp() > effectiveAnchorTs && d.getTimestamp() <= endTimestamp) {
                forwardSum += d.getDelta();
            }
        }
        float calculatedClosing = anchorBalance + forwardSum;

        // 3. Compute running balances
        Map<Long, Float> running = new LinkedHashMap<>();
        float current = calculatedOpening;
        for (TransactionDelta d : sorted) {
            current += d.getDelta();
            running.put(d.getTimestamp(), (float) (Math.round(current * 100.0) / 100.0));
        }

        return new ReconstructionResult(
                (float) (Math.round(calculatedOpening * 100.0) / 100.0),
                (float) (Math.round(calculatedClosing * 100.0) / 100.0),
                (float) (Math.round(netDelta * 100.0) / 100.0),
                anchorTimestamp,
                anchorBalance,
                running,
                true
        );
    }

    /**
     * Condenses transaction-level running balances into daily balance points for the Balance table.
     * On days that have BOTH morning inflows (hour < 12) and afternoon outflows (hour >= 12) — such as Payday —
     * both the morning post-inflow peak (at 01:00:xx UTC) AND the afternoon end-of-day balance (at 17:59:59 UTC)
     * are preserved so that payday spikes and subsequent same-day transfers are visible on /myBalances
     * and accounted for in HistoricSpentCalculator.
     */
    public static Map<Long, Float> selectDailyBalancePoints(
            Map<Long, Float> runningBalances,
            long startTimestamp,
            long endTimestamp,
            Float closingBalance) {
        if (runningBalances == null || runningBalances.isEmpty()) {
            return Collections.emptyMap();
        }

        ZoneOffset utc = ZoneOffset.UTC;
        LocalDate endDate = endTimestamp >= 86400L
                ? Instant.ofEpochSecond(endTimestamp).atZone(utc).toLocalDate()
                : null;

        Map<LocalDate, Map.Entry<Long, Float>> dailyAmLatest = new TreeMap<>();
        Map<LocalDate, Map.Entry<Long, Float>> dailyPmLatest = new TreeMap<>();
        Set<LocalDate> allDates = new TreeSet<>();

        for (Map.Entry<Long, Float> entry : runningBalances.entrySet()) {
            long ts = entry.getKey();
            if (ts > startTimestamp && ts < endTimestamp) {
                java.time.ZonedDateTime zdt = Instant.ofEpochSecond(ts).atZone(utc);
                LocalDate date = zdt.toLocalDate();
                allDates.add(date);
                if (zdt.getHour() < 12) {
                    dailyAmLatest.put(date, entry);
                } else {
                    dailyPmLatest.put(date, entry);
                }
            }
        }

        Map<Long, Float> selected = new LinkedHashMap<>();
        for (LocalDate date : allDates) {
            Map.Entry<Long, Float> am = dailyAmLatest.get(date);
            Map.Entry<Long, Float> pm = dailyPmLatest.get(date);
            boolean isEndDay = date.equals(endDate);

            if (am != null && pm != null) {
                // Day has both inflows and outflows (e.g. Payday): preserve the morning post-inflow peak!
                selected.put(am.getKey(), am.getValue());
                // Also emit the end-of-day balance after outflows (unless endDate closingBal already covers it)
                if (!isEndDay || closingBalance == null || Math.abs(pm.getValue() - closingBalance) >= 0.01f) {
                    long eodTs = date.atTime(OUTFLOW_HOUR_UTC, 59, 59).toEpochSecond(utc);
                    selected.put(eodTs, pm.getValue());
                }
            } else if (am != null) {
                if (!isEndDay || closingBalance == null || Math.abs(am.getValue() - closingBalance) >= 0.01f) {
                    long eodTs = date.atTime(OUTFLOW_HOUR_UTC, 59, 59).toEpochSecond(utc);
                    selected.put(eodTs, am.getValue());
                }
            } else if (pm != null) {
                if (!isEndDay || closingBalance == null || Math.abs(pm.getValue() - closingBalance) >= 0.01f) {
                    long eodTs = date.atTime(OUTFLOW_HOUR_UTC, 59, 59).toEpochSecond(utc);
                    selected.put(eodTs, pm.getValue());
                }
            }
        }
        return selected;
    }

    /**
     * Finds an existing anchor balance within [startTimestamp, endTimestamp] from an existing list of balances.
     * Returns Optional.empty() if no balance exists in that interval.
     */
    public static Optional<Balance> findAnchorBalance(List<Balance> existingBalances, long startTimestamp, long endTimestamp) {
        if (existingBalances == null || existingBalances.isEmpty()) {
            return Optional.empty();
        }
        return existingBalances.stream()
                .filter(b -> b.getTimestamp() != null && b.getTimestamp() >= startTimestamp && b.getTimestamp() <= endTimestamp)
                .max(Comparator.comparingLong(Balance::getTimestamp)); // Pick most recent within range
    }
}
