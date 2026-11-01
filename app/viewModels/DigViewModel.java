package viewModels;

import models.BankTransaction;

import java.util.*;

public class DigViewModel {
    private final List<BankTransaction> geotaggedTransactions;
    private final List<CategorySpend> histogram;
    private final boolean isMonzoConnected;
    private final int totalGeotagged;
    private final double totalSpent;
    private final boolean limitReached;
    private final int dataLimit;

    public DigViewModel(List<BankTransaction> geotaggedTransactions,
                        List<CategorySpend> histogram,
                        boolean isMonzoConnected,
                        boolean limitReached,
                        int dataLimit) {
        this.geotaggedTransactions = geotaggedTransactions != null ? geotaggedTransactions : Collections.emptyList();
        List<CategorySpend> effectiveHistogram = (histogram != null && !histogram.isEmpty())
                ? histogram
                : buildFallbackHistogram(this.geotaggedTransactions);
        this.histogram = effectiveHistogram;
        this.isMonzoConnected = isMonzoConnected;
        this.totalGeotagged = this.geotaggedTransactions.size();
        double sum = 0;
        for (CategorySpend cs : this.histogram) {
            sum += cs.getTotalSpent();
        }
        this.totalSpent = Math.round(sum * 100.0) / 100.0;
        this.limitReached = limitReached;
        this.dataLimit = dataLimit;
    }

    private static List<CategorySpend> buildFallbackHistogram(List<BankTransaction> txs) {
        if (txs == null || txs.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, double[]> totals = new LinkedHashMap<>();
        double grandTotal = 0.0;
        for (BankTransaction tx : txs) {
            if (tx.getAmount() == null || tx.getAmount() <= 0) continue;
            String cat = (tx.getCategory() != null && !tx.getCategory().trim().isEmpty())
                    ? tx.getCategory().trim()
                    : "General";
            counts.computeIfAbsent(cat, k -> new long[1])[0] += 1L;
            totals.computeIfAbsent(cat, k -> new double[1])[0] += tx.getAmount();
            grandTotal += tx.getAmount();
        }
        List<CategorySpend> list = new ArrayList<>();
        for (String cat : totals.keySet()) {
            long cnt = counts.get(cat)[0];
            double total = Math.round(totals.get(cat)[0] * 100.0) / 100.0;
            double avg = cnt > 0 ? Math.round((total / cnt) * 100.0) / 100.0 : 0.0;
            double pct = grandTotal > 0 ? Math.round((total / grandTotal * 100.0) * 10.0) / 10.0 : 0.0;
            list.add(new CategorySpend(cat, cnt, total, avg, pct));
        }
        list.sort((a, b) -> Double.compare(b.getTotalSpent(), a.getTotalSpent()));
        return list;
    }

    public List<BankTransaction> getGeotaggedTransactions() {
        return geotaggedTransactions;
    }

    public List<CategorySpend> getHistogram() {
        return histogram;
    }

    public boolean isMonzoConnected() {
        return isMonzoConnected;
    }

    public int getTotalGeotagged() {
        return totalGeotagged;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public boolean isLimitReached() {
        return limitReached;
    }

    public int getDataLimit() {
        return dataLimit;
    }

    public boolean hasData() {
        return totalGeotagged > 0 || (histogram != null && !histogram.isEmpty());
    }
}
