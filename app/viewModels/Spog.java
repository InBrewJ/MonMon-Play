package viewModels;

import models.Account;
import models.Balance;

import java.time.LocalDate;
import java.util.*;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.findAlreadyPaid;
import static helpers.ModelHelpers.findYetToPay;

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
    private final Double adjustedLeftPerDay;

    private final List<Account> allAccounts;

    public Spog(Float surplus,
                int nextPayday,
                int percentageIncomeAsSavings,
                Float incomingTotal,
                Float outgoingTotal,
                Float rentCost,
                Float completedOutgoingsSum,
                Float pendingOutgoingsSum,
                List<Account> allAccounts) {
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
        this.allAccounts = allAccounts;
        this.adjustedLeftPerDay = this.calculateAdjustedLeftPerDay();
    }

    private Double calculateAdjustedLeftPerDay() {
        HashMap<Account, AccountStatus> accountsMap = this.getAllAccounts();
        Double totalAvailableDebit = 0d;
        Double totalAvailableCredit = 0d;
        for (Map.Entry<Account, AccountStatus> pair : accountsMap.entrySet()) {
            Account a = pair.getKey();
            AccountStatus as = pair.getValue();
            Account.AccountType accountType = a.getType();
            switch (accountType) {
                case DEBIT:
                    totalAvailableDebit += as.getAdjustedAvailable();
                    break;
                case CREDIT:
                    totalAvailableCredit += as.getAdjustedAvailable();
                    break;
                default:
                    break;
            }
        }
        System.out.println("totalAvailableDebit :: " + totalAvailableDebit);
        System.out.println("totalAvailableCredit :: " + totalAvailableCredit);
        return round2(totalAvailableDebit / this.daysUntilNextPayday);
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

    public Double getAdjustedLeftPerDay() {
        return adjustedLeftPerDay;
    }

    public HashMap<Account, AccountStatus> getAllAccounts() {
        HashMap<Account, AccountStatus> accountsAndPendings = new LinkedHashMap<>();
        for (Account a : allAccounts) {
            Float alreadyPaidSum = findAlreadyPaid(a.outgoings, LocalDate.now(), nextPayday)
                            .stream()
                            .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
            Float yetToPaySum = findYetToPay(a.outgoings, LocalDate.now(), nextPayday)
                    .stream()
                    .reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
            Double latestBalance;
            try {
                a.balances.sort(Comparator.comparing(Balance::getTimestamp).reversed());
                latestBalance = a.balances.get(0).getValue();
            } catch (Exception e) {
                latestBalance = 0d;
            }
            accountsAndPendings.put(a, new AccountStatus(alreadyPaidSum, yetToPaySum, latestBalance));
        }
        return accountsAndPendings;
    }
}
