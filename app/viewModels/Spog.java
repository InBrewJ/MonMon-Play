package viewModels;

import java.time.LocalDate;

public class Spog {
    private final Float surplus;
    private final int nextPayday;
    private final int percentageIncomeAsSavings;
    private final int daysUntilNextPayday;
    private final int daysBetweenPaydays;
    private final Float incomingTotal;
    private final Float maxPerDay;
    private final Float maxPerWeek;
    private final Float yearlySurplus;
    private final Float yearlyOutgoings;
    private final LocalDate nextPayDate;
    private final Float yearlyTakehome;

    public Spog(Float surplus, int nextPayday, int percentageIncomeAsSavings, Float incomingTotal, Float outgoingTotal) {
        LocalDate now = LocalDate.now();
        this.surplus = surplus;
        this.nextPayday = nextPayday;
        this.daysUntilNextPayday = this.calculateDaysUntilNextPayday(nextPayday, now);
        this.nextPayDate = this.calculateNextPaydayDate(nextPayday, now);
        this.percentageIncomeAsSavings = percentageIncomeAsSavings;
        this.incomingTotal = incomingTotal;
        this.daysBetweenPaydays = this.calculateDaysBetweenPaydays(nextPayday, now);
        this.maxPerDay = surplus / this.daysBetweenPaydays;
        this.maxPerWeek = this.maxPerDay * 7;
        this.yearlySurplus = surplus * 12;
        this.yearlyOutgoings = outgoingTotal * 12;
        this.yearlyTakehome = incomingTotal * 12;
    }


    private int calculateDaysBetweenPaydays(int nextPayday, LocalDate now) {
        // if NOW is in the same month as the payday...
        return 31;
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
}
