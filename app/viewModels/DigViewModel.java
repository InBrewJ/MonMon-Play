package viewModels;

import helpers.BankHolidayHelper;
import helpers.ModelHelpers;
import models.BankTransaction;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class DigViewModel {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final List<BankTransaction> geotaggedTransactions;
    private final List<CategorySpend> histogram;
    private final boolean isMonzoConnected;
    private final int totalGeotagged;
    private final double totalSpent;
    private final boolean limitReached;
    private final int dataLimit;
    private final int nominalPayday;

    private final String thisMonthStartDate;
    private final String thisMonthEndDate;
    private final String thisMonthDateRangeLabel;

    private final String last2CyclesStartDate;
    private final String last2CyclesEndDate;
    private final String last2CyclesDateRangeLabel;

    private final String last6CyclesStartDate;
    private final String last6CyclesEndDate;
    private final String last6CyclesDateRangeLabel;

    public DigViewModel(List<BankTransaction> geotaggedTransactions,
                        List<CategorySpend> histogram,
                        boolean isMonzoConnected,
                        boolean limitReached,
                        int dataLimit,
                        int nominalPayday) {
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

        int effectivePayday = (nominalPayday >= 1 && nominalPayday <= 31) ? nominalPayday : 1;
        this.nominalPayday = effectivePayday;

        LocalDate today = BankHolidayHelper.nowInRegion("UK");
        LocalDate thisMonthStart = ModelHelpers.findLastPaydayDate(today, effectivePayday);
        LocalDate thisMonthEnd = today;

        LocalDate last2CyclesStart = ModelHelpers.findLastPaydayDate(thisMonthStart.minusDays(1), effectivePayday);
        LocalDate last2CyclesEnd = today;

        LocalDate last6CyclesStart = thisMonthStart;
        for (int i = 0; i < 5; i++) {
            last6CyclesStart = ModelHelpers.findLastPaydayDate(last6CyclesStart.minusDays(1), effectivePayday);
        }
        LocalDate last6CyclesEnd = today;

        this.thisMonthStartDate = thisMonthStart.format(ISO_FORMATTER);
        this.thisMonthEndDate = thisMonthEnd.format(ISO_FORMATTER);
        this.thisMonthDateRangeLabel = formatRange(thisMonthStart, thisMonthEnd);

        this.last2CyclesStartDate = last2CyclesStart.format(ISO_FORMATTER);
        this.last2CyclesEndDate = last2CyclesEnd.format(ISO_FORMATTER);
        this.last2CyclesDateRangeLabel = formatRange(last2CyclesStart, last2CyclesEnd);

        this.last6CyclesStartDate = last6CyclesStart.format(ISO_FORMATTER);
        this.last6CyclesEndDate = last6CyclesEnd.format(ISO_FORMATTER);
        this.last6CyclesDateRangeLabel = formatRange(last6CyclesStart, last6CyclesEnd);
    }

    public DigViewModel(List<BankTransaction> geotaggedTransactions,
                        List<CategorySpend> histogram,
                        boolean isMonzoConnected,
                        boolean limitReached,
                        int dataLimit) {
        this(geotaggedTransactions, histogram, isMonzoConnected, limitReached, dataLimit, 1);
    }

    private static String formatRange(LocalDate start, LocalDate end) {
        if (start.getYear() == end.getYear()) {
            return start.format(DateTimeFormatter.ofPattern("d MMM")) + " – " + end.format(DISPLAY_FORMATTER);
        } else {
            return start.format(DISPLAY_FORMATTER) + " – " + end.format(DISPLAY_FORMATTER);
        }
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

    public int getNominalPayday() {
        return nominalPayday;
    }

    public String getThisMonthStartDate() {
        return thisMonthStartDate;
    }

    public String getThisMonthEndDate() {
        return thisMonthEndDate;
    }

    public String getThisMonthDateRangeLabel() {
        return thisMonthDateRangeLabel;
    }

    public String getLast2CyclesStartDate() {
        return last2CyclesStartDate;
    }

    public String getLast2CyclesEndDate() {
        return last2CyclesEndDate;
    }

    public String getLast2CyclesDateRangeLabel() {
        return last2CyclesDateRangeLabel;
    }

    public String getLast6CyclesStartDate() {
        return last6CyclesStartDate;
    }

    public String getLast6CyclesEndDate() {
        return last6CyclesEndDate;
    }

    public String getLast6CyclesDateRangeLabel() {
        return last6CyclesDateRangeLabel;
    }

    public boolean hasData() {
        return totalGeotagged > 0 || (histogram != null && !histogram.isEmpty());
    }
}
