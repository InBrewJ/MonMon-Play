package viewModels;

import models.Account;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
    private final java.util.List<SectionViewModel> sections = new java.util.ArrayList<>();

    // Optional category accounts & actual spend
    private String foodAccountName;
    private String fuelAccountName;
    private String transportAccountName;
    private String funAccountName;
    private Double actualFoodSpent;
    private Double actualFuelSpent;
    private Double actualTransportSpent;
    private Double actualFunSpent;

    public static class SectionViewModel {
        private final String heading;
        private final Float plannedAmount;
        private final Long accountId;
        private String accountName;
        private Double actualSpent;

        public SectionViewModel(String heading, Float plannedAmount, Long accountId) {
            this.heading = heading != null ? heading.trim() : "";
            this.plannedAmount = plannedAmount != null ? plannedAmount : 0.0f;
            this.accountId = (accountId != null && accountId > 0) ? accountId : null;
            this.actualSpent = 0.0;
        }

        public String getHeading() {
            return heading;
        }

        public Float getPlannedAmount() {
            return round2(plannedAmount);
        }

        public Long getAccountId() {
            return accountId;
        }

        public boolean hasAssignedAccount() {
            return accountName != null;
        }

        public String getAccountName() {
            return accountName;
        }

        public void setAccountName(String accountName) {
            this.accountName = accountName;
        }

        public Double getActualSpent() {
            return actualSpent != null ? round2(actualSpent) : 0.0;
        }

        public void setActualSpent(Double actualSpent) {
            this.actualSpent = actualSpent != null ? actualSpent : 0.0;
        }

        public Float getRemaining(int daysUntilNextPayday, int daysInCycle) {
            if (hasAssignedAccount()) {
                return round2(Math.max(0.0f, plannedAmount - getActualSpent().floatValue()));
            }
            if (daysInCycle <= 0) {
                return getPlannedAmount();
            }
            float fraction = Math.min(1.0f, (float) Math.max(0, daysUntilNextPayday) / (float) daysInCycle);
            return round2(getPlannedAmount() * fraction);
        }

        public Float getRemaining() {
            return getRemaining(0, 0);
        }
    }

    public SpendingPlan(
            String name,
            java.util.List<models.SpendingPlanSection> sectionsList,
            Float customFunSpend,
            Float maxMonthlyAllowance,
            Double spentThisMonth,
            int daysUntilNextPayday
    ) {
        this.name = name != null && !name.trim().isEmpty() ? name.trim() : "Custom";
        this.customFunSpend = customFunSpend;
        this.maxMonthlyAllowance = maxMonthlyAllowance != null ? maxMonthlyAllowance : 0.0f;
        this.spentThisMonth = spentThisMonth != null ? spentThisMonth : 0.0;
        this.daysUntilNextPayday = Math.max(0, daysUntilNextPayday);

        float food = 0f, fuel = 0f, trans = 0f;
        if (sectionsList != null) {
            for (models.SpendingPlanSection s : sectionsList) {
                SectionViewModel vm = new SectionViewModel(s.getHeading(), s.getAmount(), s.getAccountId());
                this.sections.add(vm);
                if ("food".equalsIgnoreCase(s.getHeading())) food = s.getAmount();
                else if ("fuel".equalsIgnoreCase(s.getHeading())) fuel = s.getAmount();
                else if ("transport".equalsIgnoreCase(s.getHeading())) trans = s.getAmount();
            }
        }
        this.foodSpend = food;
        this.fuelSpend = fuel;
        this.transportSpend = trans;
    }

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

        if (this.foodSpend > 0f) {
            this.sections.add(new SectionViewModel("Food", this.foodSpend, null));
        }
        if (this.fuelSpend > 0f) {
            this.sections.add(new SectionViewModel("Fuel", this.fuelSpend, null));
        }
        if (this.transportSpend > 0f) {
            this.sections.add(new SectionViewModel("Transport", this.transportSpend, null));
        }
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
        sp.foodAccountName = this.foodAccountName;
        sp.fuelAccountName = this.fuelAccountName;
        sp.transportAccountName = this.transportAccountName;
        sp.funAccountName = this.funAccountName;
        sp.actualFoodSpent = this.actualFoodSpent;
        sp.actualFuelSpent = this.actualFuelSpent;
        sp.actualTransportSpent = this.actualTransportSpent;
        sp.actualFunSpent = this.actualFunSpent;
        return sp;
    }

    public SpendingPlan withContext(Float maxMonthlyAllowance, Double spentThisMonth, int daysUntilNextPayday) {
        SpendingPlan sp = new SpendingPlan(
                this.name,
                this.foodSpend,
                this.fuelSpend,
                this.transportSpend,
                this.customFunSpend,
                maxMonthlyAllowance,
                spentThisMonth,
                daysUntilNextPayday
        );
        sp.daysInCycle = this.daysInCycle;
        sp.foodAccountName = this.foodAccountName;
        sp.fuelAccountName = this.fuelAccountName;
        sp.transportAccountName = this.transportAccountName;
        sp.funAccountName = this.funAccountName;
        sp.actualFoodSpent = this.actualFoodSpent;
        sp.actualFuelSpent = this.actualFuelSpent;
        sp.actualTransportSpent = this.actualTransportSpent;
        sp.actualFunSpent = this.actualFunSpent;
        sp.sections.clear();
        for (SectionViewModel s : this.sections) {
            SectionViewModel copy = new SectionViewModel(s.getHeading(), s.getPlannedAmount(), s.getAccountId());
            copy.setAccountName(s.getAccountName());
            copy.setActualSpent(s.getActualSpent());
            sp.sections.add(copy);
        }
        return sp;
    }

    public List<SectionViewModel> getSections() {
        return sections;
    }

    public boolean hasCustomSections() {
        return !sections.isEmpty();
    }

    public SpendingPlan withCustomTracking(java.util.Map<Long, Account> accountById, java.util.Map<Long, Double> spentByAccount) {
        for (SectionViewModel s : sections) {
            if (s.getAccountId() != null) {
                Account acc = accountById != null ? accountById.get(s.getAccountId()) : null;
                if (acc != null) {
                    s.setAccountName(acc.getName());
                    Double spent = spentByAccount != null ? spentByAccount.get(acc.getId()) : null;
                    s.setActualSpent(spent != null ? spent : 0.0);
                }
            }
        }
        return this;
    }

    public SpendingPlan withCategoryTracking(
            Account foodAccount,
            Account fuelAccount,
            Account transportAccount,
            Account funAccount,
            Double actualFoodSpent,
            Double actualFuelSpent,
            Double actualTransportSpent,
            Double actualFunSpent
    ) {
        if (foodAccount != null) {
            this.foodAccountName = foodAccount.getName();
            this.actualFoodSpent = actualFoodSpent != null ? actualFoodSpent : 0.0;
        }
        if (fuelAccount != null) {
            this.fuelAccountName = fuelAccount.getName();
            this.actualFuelSpent = actualFuelSpent != null ? actualFuelSpent : 0.0;
        }
        if (transportAccount != null) {
            this.transportAccountName = transportAccount.getName();
            this.actualTransportSpent = actualTransportSpent != null ? actualTransportSpent : 0.0;
        }
        if (funAccount != null) {
            this.funAccountName = funAccount.getName();
            this.actualFunSpent = actualFunSpent != null ? actualFunSpent : 0.0;
        }
        return this;
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
        if (!sections.isEmpty()) {
            float sum = 0f;
            for (SectionViewModel s : sections) {
                if (s.getPlannedAmount() != null) {
                    sum += s.getPlannedAmount();
                }
            }
            return round2(sum);
        }
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

    // Category tracking flags
    public boolean hasAssignedFoodAccount() {
        return foodAccountName != null;
    }

    public boolean hasAssignedFuelAccount() {
        return fuelAccountName != null;
    }

    public boolean hasAssignedTransportAccount() {
        return transportAccountName != null;
    }

    public boolean hasAssignedFunAccount() {
        return funAccountName != null;
    }

    public boolean hasAnyAssignedAccount() {
        return hasAssignedFoodAccount() || hasAssignedFuelAccount() || hasAssignedTransportAccount() || hasAssignedFunAccount();
    }

    public String getFoodAccountName() {
        return foodAccountName;
    }

    public String getFuelAccountName() {
        return fuelAccountName;
    }

    public String getTransportAccountName() {
        return transportAccountName;
    }

    public String getFunAccountName() {
        return funAccountName;
    }

    public Double getActualFoodSpent() {
        return actualFoodSpent != null ? round2(actualFoodSpent) : 0.0;
    }

    public Double getActualFuelSpent() {
        return actualFuelSpent != null ? round2(actualFuelSpent) : 0.0;
    }

    public Double getActualTransportSpent() {
        return actualTransportSpent != null ? round2(actualTransportSpent) : 0.0;
    }

    public Double getActualFunSpent() {
        return actualFunSpent != null ? round2(actualFunSpent) : 0.0;
    }

    public Double getSpentFood() {
        return getActualFoodSpent();
    }

    public Double getSpentFuel() {
        return getActualFuelSpent();
    }

    public Double getSpentTransport() {
        return getActualTransportSpent();
    }

    public Double getSpentFun() {
        return getActualFunSpent();
    }

    public Float getRemainingFood() {
        if (hasAssignedFoodAccount()) {
            return round2(Math.max(0.0f, getPlannedFoodSpend() - getActualFoodSpent().floatValue()));
        }
        if (daysInCycle <= 0) {
            return getPlannedFoodSpend();
        }
        float fraction = Math.min(1.0f, (float) daysUntilNextPayday / (float) daysInCycle);
        return round2(getPlannedFoodSpend() * fraction);
    }

    public Float getRemainingFuel() {
        if (hasAssignedFuelAccount()) {
            return round2(Math.max(0.0f, getPlannedFuelSpend() - getActualFuelSpent().floatValue()));
        }
        if (daysInCycle <= 0) {
            return getPlannedFuelSpend();
        }
        float fraction = Math.min(1.0f, (float) daysUntilNextPayday / (float) daysInCycle);
        return round2(getPlannedFuelSpend() * fraction);
    }

    public Float getRemainingTransport() {
        if (hasAssignedTransportAccount()) {
            return round2(Math.max(0.0f, getPlannedTransportSpend() - getActualTransportSpent().floatValue()));
        }
        if (daysInCycle <= 0) {
            return getPlannedTransportSpend();
        }
        float fraction = Math.min(1.0f, (float) daysUntilNextPayday / (float) daysInCycle);
        return round2(getPlannedTransportSpend() * fraction);
    }

    /**
     * Essentials still to cover for this cycle. If dedicated accounts are assigned, uses the
     * actual unspent portion of each category. Otherwise reserves the pro-rated daily share.
     */
    public Float getRemainingEssentials() {
        if (!sections.isEmpty()) {
            float sum = 0f;
            for (SectionViewModel s : sections) {
                sum += s.getRemaining(daysUntilNextPayday, daysInCycle);
            }
            return round2(sum);
        }
        return round2(getRemainingFood() + getRemainingFuel() + getRemainingTransport());
    }

    public Float getAdjustedFun() {
        if (hasAssignedFunAccount()) {
            return round2(getPlannedFun() - getActualFunSpent().floatValue());
        }
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
        return round2((double) getRemainingFood() / daysUntilNextPayday);
    }

    public int getDaysUntilNextPayday() {
        return daysUntilNextPayday;
    }

    public int getDaysInCycle() {
        return daysInCycle;
    }
}
