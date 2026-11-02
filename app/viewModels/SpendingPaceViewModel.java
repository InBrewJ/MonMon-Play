package viewModels;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static helpers.MathHelpers.round2;

public class SpendingPaceViewModel {
    private static final DateTimeFormatter LONG_DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final LocalDate cycleStartDate;
    private final LocalDate cycleEndDate;
    private final boolean isCurrentCycle;
    private final int daysInCycle;
    private final int daysElapsed;
    private final float monthlyBudget;
    private final float maxPerDay;
    private final double totalSpent;
    private final double actualDailySpend;
    private final int cycleOffset;
    private final List<SpendingPaceChunk> chunks;
    private final List<HistoricCycleSpent> availableCycles;

    public SpendingPaceViewModel(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            float monthlyBudget,
            float maxPerDay,
            double totalSpent,
            double actualDailySpend,
            int cycleOffset,
            List<SpendingPaceChunk> chunks,
            List<HistoricCycleSpent> availableCycles
    ) {
        this.cycleStartDate = cycleStartDate;
        this.cycleEndDate = cycleEndDate;
        this.isCurrentCycle = isCurrentCycle;
        this.daysInCycle = daysInCycle;
        this.daysElapsed = daysElapsed;
        this.monthlyBudget = round2(monthlyBudget);
        this.maxPerDay = round2(maxPerDay);
        this.totalSpent = round2(totalSpent);
        this.actualDailySpend = round2(actualDailySpend);
        this.cycleOffset = cycleOffset;
        this.chunks = chunks != null ? chunks : Collections.emptyList();
        this.availableCycles = availableCycles != null ? availableCycles : Collections.emptyList();
    }

    public LocalDate getCycleStartDate() {
        return cycleStartDate;
    }

    public LocalDate getCycleEndDate() {
        return cycleEndDate;
    }

    public boolean isCurrentCycle() {
        return isCurrentCycle;
    }

    public int getDaysInCycle() {
        return daysInCycle;
    }

    public int getDaysElapsed() {
        return daysElapsed;
    }

    public float getMonthlyBudget() {
        return monthlyBudget;
    }

    public float getMaxPerDay() {
        return maxPerDay;
    }

    public double getTotalSpent() {
        return totalSpent;
    }

    public double getActualDailySpend() {
        return actualDailySpend;
    }

    public int getCycleOffset() {
        return cycleOffset;
    }

    public List<SpendingPaceChunk> getChunks() {
        return chunks;
    }

    public List<HistoricCycleSpent> getAvailableCycles() {
        return availableCycles;
    }

    public double getDailyOverspend() {
        return round2(actualDailySpend - (double) maxPerDay);
    }

    public double getAbsoluteDailyOverspend() {
        return round2(Math.abs(getDailyOverspend()));
    }

    public boolean isOverspentDaily() {
        return getDailyOverspend() > 0.0;
    }

    public String getCycleDateRange() {
        if (cycleStartDate == null || cycleEndDate == null) return "";
        return cycleStartDate.format(LONG_DATE_FMT) + " – " + cycleEndDate.format(LONG_DATE_FMT);
    }

    /**
     * Serializes view model into a JSON string for interactive canvas rendering.
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"isCurrentCycle\":").append(isCurrentCycle).append(",");
        sb.append("\"daysInCycle\":").append(daysInCycle).append(",");
        sb.append("\"daysElapsed\":").append(daysElapsed).append(",");
        sb.append("\"monthlyBudget\":").append(monthlyBudget).append(",");
        sb.append("\"maxPerDay\":").append(maxPerDay).append(",");
        sb.append("\"totalSpent\":").append(totalSpent).append(",");
        sb.append("\"actualDailySpend\":").append(actualDailySpend).append(",");
        sb.append("\"cycleDateRange\":\"").append(getCycleDateRange()).append("\",");
        sb.append("\"chunks\":[");
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sb.append(",");
            SpendingPaceChunk c = chunks.get(i);
            sb.append("{");
            sb.append("\"chunkIndex\":").append(c.getChunkIndex()).append(",");
            sb.append("\"label\":\"").append(c.getLabel()).append("\",");
            sb.append("\"dateRangeStr\":\"").append(c.getDateRangeFormatted()).append("\",");
            sb.append("\"startDate\":\"").append(c.getStartDate()).append("\",");
            sb.append("\"endDate\":\"").append(c.getEndDate()).append("\",");
            sb.append("\"startDay\":").append(c.getStartDay()).append(",");
            sb.append("\"endDay\":").append(c.getEndDay()).append(",");
            sb.append("\"daysInChunk\":").append(c.getDaysInChunk()).append(",");
            sb.append("\"daysElapsed\":").append(c.getDaysElapsed()).append(",");
            sb.append("\"totalSpent\":").append(c.getTotalSpent()).append(",");
            sb.append("\"spendingPace\":").append(c.getSpendingPace()).append(",");
            sb.append("\"cumulativeSpent\":").append(c.getCumulativeSpent()).append(",");
            sb.append("\"cumulativeDaysElapsed\":").append(c.getCumulativeDaysElapsed()).append(",");
            sb.append("\"cumulativePace\":").append(c.getCumulativePace()).append(",");
            sb.append("\"targetMaxPace\":").append(c.getTargetMaxPace()).append(",");
            sb.append("\"differenceFromMax\":").append(c.getDifferenceFromMax()).append(",");
            sb.append("\"isOverBudget\":").append(c.isOverBudget()).append(",");
            sb.append("\"isCurrent\":").append(c.isCurrent()).append(",");
            sb.append("\"isFuture\":").append(c.isFuture());
            sb.append("}");
        }
        sb.append("]");
        sb.append("}");
        return sb.toString();
    }
}
