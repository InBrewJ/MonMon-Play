package viewModels;

import static helpers.MathHelpers.round2;

public class AccountSpentItem {
    private final String accountName;
    private final String accountType;
    private final Double startBalance;
    private final Double endBalance;
    private final Double amountSpent;

    public AccountSpentItem(String accountName, String accountType, Double startBalance, Double endBalance, Double amountSpent) {
        this.accountName = accountName;
        this.accountType = accountType;
        this.startBalance = round2(startBalance != null ? startBalance : 0.0);
        this.endBalance = round2(endBalance != null ? endBalance : 0.0);
        this.amountSpent = round2(amountSpent != null ? amountSpent : 0.0);
    }

    public AccountSpentItem(String accountName, String accountType, Double amountSpent) {
        this(accountName, accountType, 0.0, 0.0, amountSpent);
    }

    public String getAccountName() {
        return accountName;
    }

    public String getAccountType() {
        return accountType;
    }

    public Double getStartBalance() {
        return startBalance;
    }

    public Double getEndBalance() {
        return endBalance;
    }

    public Double getAmountSpent() {
        return amountSpent;
    }
}
