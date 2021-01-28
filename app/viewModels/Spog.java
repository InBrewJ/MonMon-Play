package viewModels;

import java.time.LocalDate;

import static helpers.MathHelpers.round2;

public class Spog {
    private final Float surplus;
    private final int nextPayday;
    private final int percentageIncomeAsSavings;
    private final Float percentageIncomeAsRent;
    private final int daysUntilNextPayday;
    private final int daysBetweenPaydays;
    private final Float incomingTotal;
    private final Float maxPerDay;
    private final Float maxPerWeek;
    private final Float yearlySurplus;
    private final Float yearlyOutgoings;
    private final LocalDate nextPayDate;
    private final Float yearlyTakehome;
    private final Float rentCost;
    private final Float completedOutgoings;
    private final Float pendingOutgoings;

    public Spog(Float surplus,
                int nextPayday,
                int percentageIncomeAsSavings,
                Float incomingTotal,
                Float outgoingTotal,
                Float rentCost,
                Float completedOutgoingsSum,
                Float pendingOutgoingsSum) {
        LocalDate now = LocalDate.now();
        this.rentCost = rentCost;
        this.surplus = round2(surplus);
        this.nextPayday = nextPayday;
        this.daysUntilNextPayday = this.calculateDaysUntilNextPayday(nextPayday, now);
        this.nextPayDate = this.calculateNextPaydayDate(nextPayday, now);
        this.percentageIncomeAsSavings = percentageIncomeAsSavings;
        this.incomingTotal = incomingTotal;
        this.daysBetweenPaydays = this.calculateDaysBetweenPaydays(nextPayday, now);
        this.maxPerDay = round2(surplus / this.daysBetweenPaydays);
        this.maxPerWeek = round2(this.maxPerDay * 7);
        this.yearlySurplus = round2(surplus * 12);
        this.yearlyOutgoings = round2(outgoingTotal * 12);
        this.yearlyTakehome = round2(incomingTotal * 12);
        this.percentageIncomeAsRent = this.calculatePercentageIncomeAsRent();
        this.completedOutgoings = completedOutgoingsSum;
        this.pendingOutgoings = pendingOutgoingsSum;
    }

    private Float calculatePercentageIncomeAsRent() {
        return round2((this.rentCost / this.incomingTotal) * 100);
    }

    private int calculateDaysBetweenPaydays(int nextPayday, LocalDate now) {
        int daysUntilNextPayday = this.calculateDaysUntilNextPayday(nextPayday, now);
        int daysSinceLastPayday = this.calculateDaysSinceLastPayday(nextPayday, now);
        int daysBetweenPayDays = daysUntilNextPayday + daysSinceLastPayday;
        return daysBetweenPayDays;
    }

    private LocalDate calculateNextPaydayDate(int nextPayday, LocalDate from) {
        LocalDate possiblePayDate = from;
        boolean found = false;
        while(!found) {
            possiblePayDate = possiblePayDate.plusDays(1);
            if (possiblePayDate.getDayOfMonth() == nextPayday) found = true;
        }
        return possiblePayDate;
    }

    private int calculateDaysUntilNextPayday(int nextPayday, LocalDate from) {
        LocalDate possiblePayDate = from;
        boolean found = false;
        int count = 0;
        while(!found) {
            possiblePayDate = possiblePayDate.plusDays(1);
            count++;
            if (possiblePayDate.getDayOfMonth() == nextPayday) found = true;
        }
        return count;
    }

    private int calculateDaysSinceLastPayday(int lastPayday, LocalDate from) {
        LocalDate possiblePayDate = from;
        boolean found = false;
        int count = 0;
        while(!found) {
            possiblePayDate = possiblePayDate.minusDays(1);
            count++;
            if (possiblePayDate.getDayOfMonth() == lastPayday) found = true;
        }
        return count;
    }

    public Float getPercentageIncomeAsRent() {
        return percentageIncomeAsRent;
    }

    public int getDaysUntilNextPayday() {
        return daysUntilNextPayday;
    }

    public LocalDate getNextPayDate() {
        return nextPayDate;
    }

    public int getDaysBetweenPaydays() {
        return daysBetweenPaydays;
    }

    public Float getIncomingTotal() {
        return incomingTotal;
    }

    public Float getSurplus() {
        return surplus;
    }

    public int getNextPayday() {
        return nextPayday;
    }

    public int getPercentageIncomeAsSavings() {
        return percentageIncomeAsSavings;
    }

    public Float getMaxPerDay() {
        return maxPerDay;
    }

    public Float getMaxPerWeek() {
        return maxPerWeek;
    }

    public Float getYearlySurplus() {
        return yearlySurplus;
    }

    public Float getYearlyOutgoings() {
        return yearlyOutgoings;
    }

    public Float getYearlyTakehome() {
        return yearlyTakehome;
    }

    public Float getRentCost() {
        return rentCost;
    }

    public Float getCompletedOutgoings() {
        return completedOutgoings;
    }

    public Float getPendingOutgoings() {
        return pendingOutgoings;
    }
}
