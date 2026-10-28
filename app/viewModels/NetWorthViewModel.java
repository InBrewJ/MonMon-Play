package viewModels;

import models.Account;
import models.Balance;
import models.NetWorthAsset;

import java.util.*;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;

public class NetWorthViewModel {

    private final List<Account> accounts;
    private final List<NetWorthAsset> customAssets;
    private final Double monthlySurplus;
    private final Double monthlyFoodCost;

    private Double cashTotal = 0.0;
    private Double pensionTotal = 0.0;
    private Double customAssetsTotal = 0.0;
    private Double creditDebtTotal = 0.0;
    private Double loanDebtTotal = 0.0;
    private Double totalAssets = 0.0;
    private Double totalLiabilities = 0.0;
    private Double netWorth = 0.0;

    private final List<Account> cashAccounts = new ArrayList<>();
    private final List<Account> pensionAccounts = new ArrayList<>();
    private final List<Account> creditAccounts = new ArrayList<>();
    private final List<Account> loanAccounts = new ArrayList<>();

    public NetWorthViewModel(
            List<Account> accounts,
            List<NetWorthAsset> customAssets,
            Double monthlySurplus,
            Double monthlyFoodCost
    ) {
        this.accounts = accounts != null ? accounts : Collections.emptyList();
        this.customAssets = customAssets != null ? customAssets : Collections.emptyList();
        this.monthlySurplus = monthlySurplus != null ? round2(monthlySurplus) : 0.0;
        this.monthlyFoodCost = monthlyFoodCost != null && monthlyFoodCost > 0.0 ? round2(monthlyFoodCost) : 216.67; // default UK avg ~£50/wk

        calculate();
    }

    private void calculate() {
        for (Account a : accounts) {
            if (a == null || a.isArchived()) continue;
            double bal = getLatestBalance(a);

            if (a.getType() == Account.AccountType.PENSION) {
                pensionAccounts.add(a);
                pensionTotal += Math.max(0.0, bal);
            } else if (a.getType() == Account.AccountType.LOAN) {
                loanAccounts.add(a);
                loanDebtTotal += Math.max(0.0, bal);
            } else if (a.getType() == Account.AccountType.CREDIT) {
                creditAccounts.add(a);
                float limit = a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f;
                double owed = Math.max(0.0, limit - bal);
                creditDebtTotal += owed;
            } else {
                // Debit, shared bills, short-term and long-term savings
                cashAccounts.add(a);
                cashTotal += bal;
            }
        }

        for (NetWorthAsset asset : customAssets) {
            if (asset != null && !asset.isArchived()) {
                customAssetsTotal += Math.max(0.0, asset.getValue());
            }
        }

        cashTotal = round2(cashTotal);
        pensionTotal = round2(pensionTotal);
        customAssetsTotal = round2(customAssetsTotal);
        creditDebtTotal = round2(creditDebtTotal);
        loanDebtTotal = round2(loanDebtTotal);

        totalAssets = round2(cashTotal + pensionTotal + customAssetsTotal);
        totalLiabilities = round2(creditDebtTotal + loanDebtTotal);
        netWorth = round2(totalAssets - totalLiabilities);
    }

    private double getLatestBalance(Account a) {
        if (a == null || a.balances == null || a.balances.isEmpty()) {
            return 0.0;
        }
        return a.balances.stream()
                .filter(b -> b != null && b.getValue() != null && b.getTimestamp() != null)
                .max(Comparator.comparing(Balance::getTimestamp).thenComparing(b -> b.getId() != null ? b.getId() : 0L))
                .map(b -> (double) b.getValue())
                .orElse(0.0);
    }

    public List<NetWorthAsset> getCustomAssets() {
        return customAssets;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public List<Account> getCashAccounts() {
        return cashAccounts;
    }

    public List<Account> getPensionAccounts() {
        return pensionAccounts;
    }

    public List<Account> getCreditAccounts() {
        return creditAccounts;
    }

    public List<Account> getLoanAccounts() {
        return loanAccounts;
    }

    public Double getCashTotal() {
        return cashTotal;
    }

    public Double getPensionTotal() {
        return pensionTotal;
    }

    public Double getCustomAssetsTotal() {
        return customAssetsTotal;
    }

    public Double getCreditDebtTotal() {
        return creditDebtTotal;
    }

    public Double getLoanDebtTotal() {
        return loanDebtTotal;
    }

    public double getAccountOwed(Account a) {
        if (a == null) return 0.0;
        if (a.getType() == Account.AccountType.CREDIT) {
            float limit = a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f;
            double bal = getLatestBalance(a);
            return round2(Math.max(0.0, limit - bal));
        }
        return round2(getLatestBalance(a));
    }

    public Double getTotalAssets() {
        return totalAssets;
    }

    public Double getTotalLiabilities() {
        return totalLiabilities;
    }

    public Double getNetWorth() {
        return netWorth;
    }

    public Double getInvestablePortfolio() {
        return round2(cashTotal + pensionTotal);
    }

    /**
     * 4% rule annual withdrawal / yield from portfolio
     */
    public Double getAnnualSafeWithdrawal() {
        return round2(Math.max(0.0, getInvestablePortfolio() * 0.04));
    }

    /**
     * Monthly withdrawal at 4% safe withdrawal rate
     */
    public Double getMonthlySafeWithdrawal() {
        return round2(getAnnualSafeWithdrawal() / 12.0);
    }

    public Double getMonthlyFoodCost() {
        return monthlyFoodCost;
    }

    /**
     * Percentage of monthly food/groceries covered by portfolio safe withdrawal
     */
    public int getFoodCoveredPercent() {
        if (monthlyFoodCost <= 0.0) return 0;
        return (int) Math.round((getMonthlySafeWithdrawal() / monthlyFoodCost) * 100);
    }

    /**
     * Estimated target portfolio for financial independence (assuming 25x annual basic spending)
     */
    public Double getTargetFirePortfolio() {
        double basicAnnualCost = Math.max(12000.0, monthlyFoodCost * 12.0 * 2.5); // modest baseline living cost
        return round2(basicAnnualCost * 25.0);
    }

    public int getFireProgressPercent() {
        double target = getTargetFirePortfolio();
        if (target <= 0.0) return 0;
        return Math.min(100, Math.max(0, (int) Math.round((getInvestablePortfolio() / target) * 100)));
    }
}
