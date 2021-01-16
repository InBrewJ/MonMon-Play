package viewModels;

import java.time.LocalDate;

public class Spog {
    private final Float surplus;
    private final int nextPayday;
    private final int percentageIncomeAsSavings;
    private final int daysUntilNextPayday;
    private final Float incomingTotal;
    private final LocalDate nextPayDate;

    public Spog(Float surplus, int nextPayday, int percentageIncomeAsSavings, Float incomingTotal) {
        this.surplus = surplus;
        this.nextPayday = nextPayday;
        this.daysUntilNextPayday = this.calculateDaysUntilNextPayday(nextPayday, LocalDate.now());
        this.nextPayDate = this.calculateNextPaydayDate(nextPayday, LocalDate.now());
        this.percentageIncomeAsSavings = percentageIncomeAsSavings;
        this.incomingTotal = incomingTotal;
    }

    public int getDaysUntilNextPayday() {
        return daysUntilNextPayday;
    }

    public LocalDate getNextPayDate() {
        return nextPayDate;
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

}
