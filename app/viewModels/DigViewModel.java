package viewModels;

import models.BankTransaction;

import java.util.List;

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
        this.geotaggedTransactions = geotaggedTransactions;
        this.histogram = histogram;
        this.isMonzoConnected = isMonzoConnected;
        this.totalGeotagged = geotaggedTransactions != null ? geotaggedTransactions.size() : 0;
        double sum = 0;
        if (histogram != null) {
            for (CategorySpend cs : histogram) {
                sum += cs.getTotalSpent();
            }
        }
        this.totalSpent = Math.round(sum * 100.0) / 100.0;
        this.limitReached = limitReached;
        this.dataLimit = dataLimit;
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
