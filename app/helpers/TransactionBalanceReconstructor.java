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

        // Sort chronologically
        deltas.sort(Comparator.comparingLong(TransactionDelta::getTimestamp));
        return deltas;
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

        // Sort deltas chronologically
        List<TransactionDelta> sorted = new ArrayList<>(deltas);
        sorted.sort(Comparator.comparingLong(TransactionDelta::getTimestamp));

        float netDelta = 0.0f;
        for (TransactionDelta d : sorted) {
            netDelta += d.getDelta();
        }

        // 1. Backward reconstruction from anchorTimestamp to startTimestamp:
        // B(start) = B(anchor) - sum(deltas where start < t <= anchor)
        float backwardSum = 0.0f;
        for (TransactionDelta d : sorted) {
            if (d.getTimestamp() <= anchorTimestamp && d.getTimestamp() >= startTimestamp) {
                backwardSum += d.getDelta();
            }
        }
        float calculatedOpening = anchorBalance - backwardSum;

        // 2. Forward reconstruction from anchorTimestamp to endTimestamp:
        // B(end) = B(anchor) + sum(deltas where anchor < t <= end)
        float forwardSum = 0.0f;
        for (TransactionDelta d : sorted) {
            if (d.getTimestamp() > anchorTimestamp && d.getTimestamp() <= endTimestamp) {
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
