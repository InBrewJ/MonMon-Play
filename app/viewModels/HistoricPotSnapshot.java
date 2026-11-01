package viewModels;

import models.Account;
import models.Pot;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

import static helpers.MathHelpers.round2;
import static helpers.TimeHelpers.unixTimestampToDisplayDate;

public class HistoricPotSnapshot {
    private final Pot pot;
    private final boolean lastPot;
    private final boolean archivedInPeriod;
    private final Long archivedAt;
    private final Double potSpent;
    private final List<AccountBalanceSnapshot> accountSnapshots;

    public static class AccountBalanceSnapshot {
        private final String accountName;
        private final String accountType;
        private final Double balanceOnDate;
        private final Double amountSpent;

        public AccountBalanceSnapshot(String accountName, String accountType, Double balanceOnDate, Double amountSpent) {
            this.accountName = accountName != null ? accountName : "Account";
            this.accountType = accountType != null ? accountType : "DEBIT";
            this.balanceOnDate = round2(balanceOnDate != null ? balanceOnDate : 0.0);
            this.amountSpent = round2(amountSpent != null ? amountSpent : 0.0);
        }

        public String getAccountName() {
            return accountName;
        }

        public String getAccountType() {
            return accountType;
        }

        public Double getBalanceOnDate() {
            return balanceOnDate;
        }

        public Double getAmountSpent() {
            return amountSpent;
        }
    }

    public HistoricPotSnapshot(
            Pot pot,
            boolean lastPot,
            boolean archivedInPeriod,
            Long archivedAt,
            Double potSpent,
            List<AccountBalanceSnapshot> accountSnapshots
    ) {
        this.pot = pot;
        this.lastPot = lastPot;
        this.archivedInPeriod = archivedInPeriod;
        this.archivedAt = archivedAt;
        this.potSpent = round2(potSpent != null ? potSpent : 0.0);
        this.accountSnapshots = accountSnapshots != null ? accountSnapshots : Collections.emptyList();
    }

    public Pot getPot() {
        return pot;
    }

    public boolean isLastPot() {
        return lastPot;
    }

    public boolean isArchivedInPeriod() {
        return archivedInPeriod;
    }

    public Long getArchivedAt() {
        return archivedAt;
    }

    public String getArchivedDateHumanReadable() {
        if (archivedAt == null || archivedAt <= 0L) {
            return "Active";
        }
        return unixTimestampToDisplayDate(archivedAt);
    }

    public String getCreatedAtHumanReadable() {
        if (pot == null || pot.getCreatedAt() == null) {
            return "—";
        }
        return unixTimestampToDisplayDate(pot.getCreatedAt());
    }

    public static String formatSpendingPlanSummary(Pot pot) {
        if (pot == null || !pot.hasSpendingPlan()) {
            return "No Spending Plan";
        }
        String name = pot.getSpendingPlanName();
        if ("Gruelling".equalsIgnoreCase(name)) {
            return "Gruelling (UK Avg Food £50/wk, £0 Fuel/Transport)";
        }
        StringBuilder sb = new StringBuilder(name).append(" (");
        if (pot.getSpendingSections() != null && !pot.getSpendingSections().isEmpty()) {
            for (models.SpendingPlanSection sec : pot.getSpendingSections()) {
                if (sec.getAmount() != null && sec.getAmount() > 0) {
                    sb.append(sec.getHeading()).append(": £").append(sec.getAmount()).append(", ");
                }
            }
        } else {
            if (pot.getSpendingPlanFood() != null && pot.getSpendingPlanFood() > 0) {
                sb.append("Food: £").append(pot.getSpendingPlanFood()).append(", ");
            }
            if (pot.getSpendingPlanFuel() != null && pot.getSpendingPlanFuel() > 0) {
                sb.append("Fuel: £").append(pot.getSpendingPlanFuel()).append(", ");
            }
            if (pot.getSpendingPlanTransport() != null && pot.getSpendingPlanTransport() > 0) {
                sb.append("Transport: £").append(pot.getSpendingPlanTransport()).append(", ");
            }
        }
        if (sb.toString().endsWith(", ")) {
            sb.setLength(sb.length() - 2);
        }
        sb.append(")");
        return sb.toString();
    }

    public String getPlanSummary() {
        return formatSpendingPlanSummary(pot);
    }

    public Double getPotSpent() {
        return potSpent;
    }

    public List<AccountBalanceSnapshot> getAccountSnapshots() {
        return accountSnapshots;
    }
}
