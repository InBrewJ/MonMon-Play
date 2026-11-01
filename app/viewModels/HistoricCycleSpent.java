package viewModels;

import models.Pot;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static helpers.MathHelpers.round2;

public class HistoricCycleSpent {
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final LocalDate cycleStartDate;
    private final LocalDate cycleEndDate;
    private final boolean isCurrentCycle;
    private final int daysInCycle;
    private final int daysElapsed;
    private final Double totalSpent;
    private final Double actualDailySpend;
    private final Double startingPotValue;
    private final Double endingPotValue;
    private final Double startingTotalBalance;
    private final Double endingTotalBalance;
    private final Float fallbackMonthlyBudget;
    private final Pot potConfig;
    private final List<AccountSpentItem> accountBreakdowns;
    private final Double monthlyIncome;
    private final Float potAllowance;
    private final Double potSpent;
    private final List<HistoricPotSnapshot> potsInPeriod;

    public HistoricCycleSpent(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            Double totalSpent,
            Double actualDailySpend,
            Double startingPotValue,
            Double endingPotValue,
            Double startingTotalBalance,
            Double endingTotalBalance,
            Float fallbackMonthlyBudget,
            Pot potConfig,
            List<AccountSpentItem> accountBreakdowns,
            Double monthlyIncome,
            Float potAllowance,
            Double potSpent,
            List<HistoricPotSnapshot> potsInPeriod
    ) {
        this.cycleStartDate = cycleStartDate;
        this.cycleEndDate = cycleEndDate;
        this.isCurrentCycle = isCurrentCycle;
        this.daysInCycle = daysInCycle;
        this.daysElapsed = daysElapsed;
        this.totalSpent = round2(totalSpent);
        this.actualDailySpend = round2(actualDailySpend);
        this.startingPotValue = round2(startingPotValue != null ? startingPotValue : 0.0);
        this.endingPotValue = round2(endingPotValue != null ? endingPotValue : 0.0);
        this.startingTotalBalance = round2(startingTotalBalance != null ? startingTotalBalance : this.startingPotValue);
        this.endingTotalBalance = round2(endingTotalBalance != null ? endingTotalBalance : this.endingPotValue);
        this.fallbackMonthlyBudget = round2(fallbackMonthlyBudget);
        this.potConfig = potConfig;
        this.accountBreakdowns = accountBreakdowns != null ? accountBreakdowns : Collections.emptyList();
        this.monthlyIncome = round2(monthlyIncome != null ? monthlyIncome : (fallbackMonthlyBudget != null ? (double) fallbackMonthlyBudget : this.totalSpent));
        this.potAllowance = round2(potAllowance != null ? potAllowance : (fallbackMonthlyBudget != null ? fallbackMonthlyBudget : (this.startingPotValue > 0.0 ? this.startingPotValue.floatValue() : this.totalSpent.floatValue())));
        this.potSpent = round2(potSpent != null ? potSpent : this.totalSpent);
        this.potsInPeriod = potsInPeriod != null ? potsInPeriod : Collections.emptyList();
    }

    public HistoricCycleSpent(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            Double totalSpent,
            Double actualDailySpend,
            Double startingPotValue,
            Double endingPotValue,
            Double startingTotalBalance,
            Double endingTotalBalance,
            Float fallbackMonthlyBudget,
            Pot potConfig,
            List<AccountSpentItem> accountBreakdowns,
            Double monthlyIncome,
            Float potAllowance,
            Double potSpent
    ) {
        this(
                cycleStartDate,
                cycleEndDate,
                isCurrentCycle,
                daysInCycle,
                daysElapsed,
                totalSpent,
                actualDailySpend,
                startingPotValue,
                endingPotValue,
                startingTotalBalance,
                endingTotalBalance,
                fallbackMonthlyBudget,
                potConfig,
                accountBreakdowns,
                monthlyIncome,
                potAllowance,
                potSpent,
                Collections.emptyList()
        );
    }

    public HistoricCycleSpent(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            Double totalSpent,
            Double actualDailySpend,
            Double startingPotValue,
            Double endingPotValue,
            Double startingTotalBalance,
            Double endingTotalBalance,
            Float fallbackMonthlyBudget,
            Pot potConfig,
            List<AccountSpentItem> accountBreakdowns
    ) {
        this(
                cycleStartDate,
                cycleEndDate,
                isCurrentCycle,
                daysInCycle,
                daysElapsed,
                totalSpent,
                actualDailySpend,
                startingPotValue,
                endingPotValue,
                startingTotalBalance,
                endingTotalBalance,
                fallbackMonthlyBudget,
                potConfig,
                accountBreakdowns,
                fallbackMonthlyBudget != null ? (double) fallbackMonthlyBudget : totalSpent,
                fallbackMonthlyBudget != null ? fallbackMonthlyBudget : (startingPotValue != null && startingPotValue > 0.0 ? startingPotValue.floatValue() : (totalSpent != null ? totalSpent.floatValue() : 0.0f)),
                totalSpent
        );
    }

    public HistoricCycleSpent(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            Double totalSpent,
            Double actualDailySpend,
            Double startingPotValue,
            Double endingPotValue,
            Float fallbackMonthlyBudget,
            Pot potConfig,
            List<AccountSpentItem> accountBreakdowns
    ) {
        this(
                cycleStartDate,
                cycleEndDate,
                isCurrentCycle,
                daysInCycle,
                daysElapsed,
                totalSpent,
                actualDailySpend,
                startingPotValue,
                endingPotValue,
                startingPotValue,
                endingPotValue,
                fallbackMonthlyBudget,
                potConfig,
                accountBreakdowns
        );
    }

    public HistoricCycleSpent(
            LocalDate cycleStartDate,
            LocalDate cycleEndDate,
            boolean isCurrentCycle,
            int daysInCycle,
            int daysElapsed,
            Double totalSpent,
            Double actualDailySpend,
            Float monthlyBudget,
            Pot potConfig,
            List<AccountSpentItem> accountBreakdowns
    ) {
        this(
                cycleStartDate,
                cycleEndDate,
                isCurrentCycle,
                daysInCycle,
                daysElapsed,
                totalSpent,
                actualDailySpend,
                monthlyBudget != null ? (double) monthlyBudget : 0.0,
                0.0,
                monthlyBudget,
                potConfig,
                accountBreakdowns
        );
    }

    public LocalDate getCycleStartDate() {
        return cycleStartDate;
    }

    public LocalDate getCycleEndDate() {
        return cycleEndDate;
    }

    public String getCycleDateRange() {
        return cycleStartDate.format(FORMATTER) + " - " + cycleEndDate.format(FORMATTER);
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

    public Double getTotalSpent() {
        return totalSpent;
    }

    public Double getActualDailySpend() {
        return actualDailySpend;
    }

    public Double getStartingPotValue() {
        return startingPotValue;
    }

    public Double getEndingPotValue() {
        return endingPotValue;
    }

    public Double getStartingTotalBalance() {
        return startingTotalBalance != null ? startingTotalBalance : startingPotValue;
    }

    public Double getEndingTotalBalance() {
        return endingTotalBalance != null ? endingTotalBalance : endingPotValue;
    }

    public Double getMonthlyIncome() {
        return monthlyIncome != null ? monthlyIncome : (fallbackMonthlyBudget != null ? (double) fallbackMonthlyBudget : totalSpent);
    }

    public Double getIncome() {
        return getMonthlyIncome();
    }

    public Float getPotAllowance() {
        if (potAllowance != null && potAllowance > 0.0f) {
            return potAllowance;
        }
        return getMonthlyBudget();
    }

    public Double getPotSpent() {
        return potSpent != null ? potSpent : totalSpent;
    }

    public boolean isOverPotAllowance() {
        Float allowance = getPotAllowance();
        return allowance != null && allowance > 0.0f && getPotSpent() > allowance;
    }

    public Double getAbsolutePotOverspend() {
        Float allowance = getPotAllowance();
        if (allowance == null) {
            return 0.0;
        }
        return round2(Math.abs(getPotSpent() - allowance));
    }

    public Double getPotOverspend() {
        Float allowance = getPotAllowance();
        if (allowance == null) {
            return 0.0;
        }
        return round2(getPotSpent() - allowance);
    }

    /**
     * The monthly budget / pot allowance for this cycle.
     */
    public Float getMonthlyBudget() {
        if (startingPotValue > 0.0) {
            return round2(startingPotValue.floatValue());
        }
        if (potAllowance != null && potAllowance > 0.0f) {
            return potAllowance;
        }
        if (fallbackMonthlyBudget != null && fallbackMonthlyBudget > 0.0f) {
            return round2(fallbackMonthlyBudget);
        }
        return round2(totalSpent.floatValue());
    }

    public List<HistoricPotSnapshot> getPotsInPeriod() {
        return potsInPeriod;
    }

    public boolean hasMultiplePots() {
        return potsInPeriod != null && potsInPeriod.size() > 1;
    }

    public Pot getPotConfig() {
        return potConfig;
    }

    public List<AccountSpentItem> getAccountBreakdowns() {
        return accountBreakdowns;
    }

    public Float getBudgetDifference() {
        return round2(getMonthlyBudget() - totalSpent.floatValue());
    }

    public boolean isOverspent() {
        return isOverPotAllowance();
    }

    public Float getAbsoluteOverspend() {
        return round2(getAbsolutePotOverspend().floatValue());
    }

    public int getBudgetUtilizationPercent() {
        Float allowance = getPotAllowance();
        if (allowance != null && allowance > 0f) {
            return Math.min(100, Math.max(0, (int) Math.round((getPotSpent() / allowance) * 100)));
        }
        Double inc = getMonthlyIncome();
        if (inc != null && inc > 0.0) {
            return Math.min(100, Math.max(0, (int) Math.round((totalSpent / inc) * 100)));
        }
        return 0;
    }

    public String getSpendingPlanSummary() {
        return HistoricPotSnapshot.formatSpendingPlanSummary(potConfig);
    }
}
