package viewModels;

import java.time.LocalDate;

public class Spog {
    private Float surplus;
    private int nextPayday;
    private int percentageIncomeAsSavings;
    private int daysUntilNextPayday;
    private LocalDate nextPayDate;

    public Spog(Float surplus, int nextPayday, int percentageIncomeAsSavings) {
        this.surplus = surplus;
        this.nextPayday = nextPayday;
        this.daysUntilNextPayday = this.calculateDaysUntilNextPayday(nextPayday);
        this.nextPayDate = this.calculateNextPaydayDate(nextPayday);
        this.percentageIncomeAsSavings = percentageIncomeAsSavings;
    }

    public int getDaysUntilNextPayday() {
        return daysUntilNextPayday;
    }

    public LocalDate getNextPayDate() {
        return nextPayDate;
    }

    private LocalDate calculateNextPaydayDate(int nextPayday) {
        return LocalDate.now();
    }

    private int calculateDaysUntilNextPayday(int nextPayday) {
        return 20;
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
