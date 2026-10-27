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
    private final Float fallbackMonthlyBudget;
    private final Pot potConfig;
    private final List<AccountSpentItem> accountBreakdowns;

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
        this.cycleStartDate = cycleStartDate;
        this.cycleEndDate = cycleEndDate;
        this.isCurrentCycle = isCurrentCycle;
        this.daysInCycle = daysInCycle;
        this.daysElapsed = daysElapsed;
        this.totalSpent = round2(totalSpent);
        this.actualDailySpend = round2(actualDailySpend);
        this.startingPotValue = round2(startingPotValue != null ? startingPotValue : 0.0);
        this.endingPotValue = round2(endingPotValue != null ? endingPotValue : 0.0);
        this.fallbackMonthlyBudget = round2(fallbackMonthlyBudget);
        this.potConfig = potConfig;
        this.accountBreakdowns = accountBreakdowns != null ? accountBreakdowns : Collections.emptyList();
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

    /**
     * The monthly budget for this cycle: derived from the actual starting value of the pot.
     * If no starting balance was recorded (e.g. 0), falls back to surplus or totalSpent.
     */
    public Float getMonthlyBudget() {
        if (startingPotValue > 0.0) {
            return round2(startingPotValue.floatValue());
        }
        if (fallbackMonthlyBudget != null && fallbackMonthlyBudget > 0.0f) {
            return round2(fallbackMonthlyBudget);
        }
        return round2(totalSpent.floatValue());
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
        return totalSpent > getMonthlyBudget() && getMonthlyBudget() > 0.0f;
    }

    public Float getAbsoluteOverspend() {
        return round2(Math.abs(getBudgetDifference()));
    }

    public int getBudgetUtilizationPercent() {
        Float budget = getMonthlyBudget();
        if (budget == null || budget <= 0f) {
            return 0;
        }
        return Math.min(100, Math.max(0, (int) Math.round((totalSpent / budget) * 100)));
    }

    public String getSpendingPlanSummary() {
        if (potConfig == null || !potConfig.hasSpendingPlan()) {
            return "No Spending Plan";
        }
        String name = potConfig.getSpendingPlanName();
        if ("Gruelling".equalsIgnoreCase(name)) {
            return "Gruelling (UK Avg Food £50/wk, £0 Fuel/Transport)";
        }
        StringBuilder sb = new StringBuilder(name).append(" (");
        if (potConfig.getSpendingPlanFood() != null && potConfig.getSpendingPlanFood() > 0) {
            sb.append("Food: £").append(potConfig.getSpendingPlanFood()).append(", ");
        }
        if (potConfig.getSpendingPlanFuel() != null && potConfig.getSpendingPlanFuel() > 0) {
            sb.append("Fuel: £").append(potConfig.getSpendingPlanFuel()).append(", ");
        }
        if (potConfig.getSpendingPlanTransport() != null && potConfig.getSpendingPlanTransport() > 0) {
            sb.append("Transport: £").append(potConfig.getSpendingPlanTransport()).append(", ");
        }
        if (sb.toString().endsWith(", ")) {
            sb.setLength(sb.length() - 2);
        }
        sb.append(")");
        return sb.toString();
    }
}
