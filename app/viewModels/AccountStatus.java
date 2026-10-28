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

    public enum FreshnessStatus {
        FRESH,          // Balance submitted after latest payment due date
        STALE,          // Payment was due after the last balance submission
        NO_PAYMENT_DUE, // No outgoings have been due yet this cycle
        NO_BALANCE      // No balance has ever been recorded
    }

    private FreshnessStatus freshnessStatus = FreshnessStatus.NO_PAYMENT_DUE;
    private String freshnessMessage = "";
    private Long latestBalanceTimestamp;

    AccountStatus(Float alreadyPaid, Float pending, Double lastBalance, Account.AccountType accountType, Float availableLimit) {
        this.alreadyPaid = alreadyPaid;
        this.pending = pending;
        this.latestBalance = lastBalance;
        this.accountType = accountType;
        this.availableLimit = availableLimit;
        this.adjustedAvailable = round2(this.latestBalance - this.pending);
    }

    public void setFreshness(FreshnessStatus status, String message) {
        this.freshnessStatus = status != null ? status : FreshnessStatus.NO_PAYMENT_DUE;
        this.freshnessMessage = message != null ? message : "";
    }

    public FreshnessStatus getFreshnessStatus() {
        return freshnessStatus;
    }

    public String getFreshnessMessage() {
        return freshnessMessage;
    }

    public Long getLatestBalanceTimestamp() {
        return latestBalanceTimestamp;
    }

    public void setLatestBalanceTimestamp(Long latestBalanceTimestamp) {
        this.latestBalanceTimestamp = latestBalanceTimestamp;
    }

    public boolean isFresh() {
        return freshnessStatus == FreshnessStatus.FRESH;
    }

    public boolean isStale() {
        return freshnessStatus == FreshnessStatus.STALE;
    }

    public boolean isNoPaymentDue() {
        return freshnessStatus == FreshnessStatus.NO_PAYMENT_DUE;
    }

    public boolean isNoBalance() {
        return freshnessStatus == FreshnessStatus.NO_BALANCE;
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
