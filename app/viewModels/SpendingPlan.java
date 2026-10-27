package viewModels;

import static helpers.MathHelpers.round2;

public class SpendingPlan {
    // Official UK average weekly food spend per person (ONS / DEFRA statistics: ~£50/week)
    public static final float UK_AVG_WEEKLY_FOOD_SPEND = 50.00f;
    // Monthly equivalent: £50/week * 52 weeks / 12 months = £216.67/month
    public static final float UK_AVG_MONTHLY_FOOD_SPEND = 216.67f;

    private final String name;
    private final Float foodSpend;
    private final Float fuelSpend;
    private final Float transportSpend;
    private final Float customFunSpend;
    private final Float maxMonthlyAllowance;
    private final Double spentThisMonth;
    private final int daysUntilNextPayday;
    private int daysInCycle = 0;

    public SpendingPlan(
            String name,
            Float foodSpend,
            Float fuelSpend,
            Float transportSpend,
            Float customFunSpend,
            Float maxMonthlyAllowance,
            Double spentThisMonth,
            int daysUntilNextPayday
    ) {
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Custom";
        this.foodSpend = foodSpend != null ? foodSpend : 0.0f;
        this.fuelSpend = fuelSpend != null ? fuelSpend : 0.0f;
        this.transportSpend = transportSpend != null ? transportSpend : 0.0f;
        this.customFunSpend = customFunSpend;
        this.maxMonthlyAllowance = maxMonthlyAllowance != null ? maxMonthlyAllowance : 0.0f;
        this.spentThisMonth = spentThisMonth != null ? spentThisMonth : 0.0;
        this.daysUntilNextPayday = Math.max(0, daysUntilNextPayday);
    }

    public static SpendingPlan createGruelling(Float maxMonthlyAllowance, Double spentThisMonth, int daysUntilNextPayday) {
        return new SpendingPlan(
                "Gruelling",
                UK_AVG_MONTHLY_FOOD_SPEND,
                0.0f,
                0.0f,
                null,
                maxMonthlyAllowance,
                spentThisMonth,
                daysUntilNextPayday
        );
    }

    public SpendingPlan withContext(Float maxMonthlyAllowance, Double spentThisMonth, int daysUntilNextPayday, int daysInCycle) {
        SpendingPlan sp = withContext(maxMonthlyAllowance, spentThisMonth, daysUntilNextPayday);
        sp.daysInCycle = Math.max(0, daysInCycle);
        return sp;
    }

    public SpendingPlan withContext(Float maxMonthlyAllowance, Double spentThisMonth, int daysUntilNextPayday) {
        return new SpendingPlan(
                this.name,
                this.foodSpend,
                this.fuelSpend,
                this.transportSpend,
                this.customFunSpend,
                maxMonthlyAllowance,
                spentThisMonth,
                daysUntilNextPayday
        );
    }

    public String getName() {
        return name;
    }

    public boolean isGruelling() {
        return "Gruelling".equalsIgnoreCase(name);
    }

    public Float getPlannedFoodSpend() {
        return round2(foodSpend);
    }

    public Float getWeeklyFoodSpend() {
        return round2(foodSpend * 12.0f / 52.0f);
    }

    public Float getPlannedFuelSpend() {
        return round2(fuelSpend);
    }

    public Float getPlannedTransportSpend() {
        return round2(transportSpend);
    }

    public Float getTotalPlannedEssentials() {
        return round2(getPlannedFoodSpend() + getPlannedFuelSpend() + getPlannedTransportSpend());
    }

    public Float getMaxMonthlyAllowance() {
        return round2(maxMonthlyAllowance);
    }

    public Double getSpentThisMonth() {
        return round2(spentThisMonth);
    }

    public Float getPlannedFun() {
        if (customFunSpend != null) {
            return round2(customFunSpend);
        }
        return round2(Math.max(0.0f, maxMonthlyAllowance - getTotalPlannedEssentials()));
    }

    public Float getAdjustedRemainingAllowance() {
        return round2(maxMonthlyAllowance - spentThisMonth.floatValue());
    }

    /**
     * Essentials still to be paid for this cycle. 'Spent this month' already includes the
     * food/fuel/transport bought so far, so only the remaining days' share is reserved.
     * Without cycle info (daysInCycle == 0) the full monthly amount is reserved.
     */
    public Float getRemainingEssentials() {
        if (daysInCycle <= 0) {
            return getTotalPlannedEssentials();
        }
        float fraction = Math.min(1.0f, (float) daysUntilNextPayday / (float) daysInCycle);
        return round2(getTotalPlannedEssentials() * fraction);
    }

    public Float getAdjustedFun() {
        return round2(getAdjustedRemainingAllowance() - getRemainingEssentials());
    }

    public boolean isDeficit() {
        return getAdjustedFun() < 0.0f;
    }

    public Float getAbsoluteDeficit() {
        return round2(Math.abs(getAdjustedFun()));
    }

    public Double getAdjustedFunLeftPerDay() {
        if (daysUntilNextPayday <= 0 || isDeficit()) {
            return 0.0;
        }
        return round2((double) getAdjustedFun() / daysUntilNextPayday);
    }

    public Double getFoodLeftPerDay() {
        if (daysUntilNextPayday <= 0) {
            return 0.0;
        }
        return round2((double) getPlannedFoodSpend() / daysUntilNextPayday);
    }

    public int getDaysUntilNextPayday() {
        return daysUntilNextPayday;
    }
}
