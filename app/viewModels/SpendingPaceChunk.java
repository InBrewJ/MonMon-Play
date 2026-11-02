package viewModels;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static helpers.MathHelpers.round2;

public class SpendingPaceChunk {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM");

    private final int chunkIndex;
    private final String label;
    private final LocalDate startDate;
    private final LocalDate endDate;
    private final int startDay;
    private final int endDay;
    private final int daysInChunk;
    private final int daysElapsed;
    private final double totalSpent;
    private final double spendingPace;
    private final double cumulativeSpent;
    private final int cumulativeDaysElapsed;
    private final double cumulativePace;
    private final float targetMaxPace;
    private final boolean isCurrent;
    private final boolean isFuture;

    public SpendingPaceChunk(
            int chunkIndex,
            String label,
            LocalDate startDate,
            LocalDate endDate,
            int startDay,
            int endDay,
            int daysInChunk,
            int daysElapsed,
            double totalSpent,
            double spendingPace,
            double cumulativeSpent,
            int cumulativeDaysElapsed,
            double cumulativePace,
            float targetMaxPace,
            boolean isCurrent,
            boolean isFuture
    ) {
        this.chunkIndex = chunkIndex;
        this.label = label;
        this.startDate = startDate;
        this.endDate = endDate;
        this.startDay = startDay;
        this.endDay = endDay;
        this.daysInChunk = daysInChunk;
        this.daysElapsed = daysElapsed;
        this.totalSpent = round2(totalSpent);
        this.spendingPace = round2(spendingPace);
        this.cumulativeSpent = round2(cumulativeSpent);
        this.cumulativeDaysElapsed = cumulativeDaysElapsed;
        this.cumulativePace = round2(cumulativePace);
        this.targetMaxPace = round2(targetMaxPace);
        this.isCurrent = isCurrent;
        this.isFuture = isFuture;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public String getLabel() {
        return label;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public int getStartDay() {
        return startDay;
    }

    public int getEndDay() {
        return endDay;
    }

    public int getDaysInChunk() {
        return daysInChunk;
    }

    public int getDaysElapsed() {
        return daysElapsed;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public double getSpendingPace() {
        return spendingPace;
    }

    public double getCumulativeSpent() {
        return cumulativeSpent;
    }

    public int getCumulativeDaysElapsed() {
        return cumulativeDaysElapsed;
    }

    public double getCumulativePace() {
        return cumulativePace;
    }

    public float getTargetMaxPace() {
        return targetMaxPace;
    }

    public boolean isCurrent() {
        return isCurrent;
    }

    public boolean isFuture() {
        return isFuture;
    }

    public boolean isComplete() {
        return !isFuture && daysElapsed >= daysInChunk;
    }

    public double getDifferenceFromMax() {
        return round2(spendingPace - (double) targetMaxPace);
    }

    public double getAbsoluteDifferenceFromMax() {
        return round2(Math.abs(getDifferenceFromMax()));
    }

    public boolean isOverBudget() {
        return spendingPace > (double) targetMaxPace;
    }

    public String getDateRangeFormatted() {
        if (startDate == null || endDate == null) return "";
        if (startDate.equals(endDate)) {
            return startDate.format(DATE_FMT);
        }
        return startDate.format(DATE_FMT) + " – " + endDate.format(DATE_FMT);
    }
}
