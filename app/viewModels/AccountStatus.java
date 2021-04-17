package viewModels;

import models.Account;

import static helpers.MathHelpers.round2;

public class AccountStatus {
    private final Float alreadyPaid;
    private final Float pending;
    private final Double latestBalance;
    private final Double adjustedAvailable;
    private final Float availableLimit;
    private final Account.AccountType accountType;

    AccountStatus(Float alreadyPaid, Float pending, Double lastBalance, Account.AccountType accountType, Float availableLimit) {
        this.alreadyPaid = alreadyPaid;
        this.pending = pending;
        this.latestBalance = lastBalance;
        this.accountType = accountType;
        this.availableLimit = availableLimit;
        this.adjustedAvailable = round2(this.latestBalance - this.pending);
    }

    public Float getAlreadyPaid() {
        return round2(alreadyPaid);
    }

    public Float getPending() {
        return round2(pending);
    }

    public Double getLatestBalance() {
        return round2(latestBalance);
    }

    public Double getAdjustedAvailable() {
        return adjustedAvailable;
    }

    public Account.AccountType getAccountType() {
        return accountType;
    }

    public Double getBalanceWithLimits() {
        Float safeAvailableLimit = availableLimit == null ? 0 : availableLimit;
        Double safeLatestBalance = latestBalance == null ? 0 : latestBalance;
        switch (accountType) {
            case CREDIT:
                // for a credit limit
                return round2(safeAvailableLimit - safeLatestBalance);
            case DEBIT: case DEBIT_SHARED_BILLS:
                // for an overdraft
                return round2(safeAvailableLimit + safeLatestBalance);
            default:
                // for anything else, as yet undefined
                return 0d;
        }
    }
}
