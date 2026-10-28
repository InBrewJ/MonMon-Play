package models;

import com.fasterxml.jackson.annotation.JsonIgnore;

import javax.persistence.*;

import static helpers.MathHelpers.round2;

@Entity
@Table(name = "savings_plan_item")
public class SavingsPlanItem {

    public enum ItemType {
        DEBT_PAYOFF,
        SAVINGS_TARGET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "savings_plan_id", nullable = false)
    @JsonIgnore
    private SavingsPlan savingsPlan;

    private String name;

    @Enumerated(EnumType.STRING)
    private ItemType type = ItemType.SAVINGS_TARGET;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id", nullable = true)
    private Account account;

    private Float targetAmount = 0.0f;

    private Float monthlyAmount = 0.0f;

    private Integer priority = 1;

    private boolean locked = false;

    private boolean archived = false;

    public SavingsPlanItem() {
    }

    public SavingsPlanItem(String name, ItemType type, Account account, Float targetAmount, Float monthlyAmount) {
        this(name, type, account, targetAmount, monthlyAmount, 1);
    }

    public SavingsPlanItem(String name, ItemType type, Account account, Float targetAmount, Float monthlyAmount, Integer priority) {
        this.name = name;
        this.type = type != null ? type : ItemType.SAVINGS_TARGET;
        this.account = account;
        this.targetAmount = targetAmount != null ? targetAmount : 0.0f;
        this.monthlyAmount = monthlyAmount != null ? monthlyAmount : 0.0f;
        this.priority = priority != null && priority > 0 ? priority : 1;
        this.archived = false;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SavingsPlanItem that = (SavingsPlanItem) o;
        if (id != null && that.id != null) {
            return id.equals(that.id);
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return id != null ? id.hashCode() : super.hashCode();
    }

    public SavingsPlan getSavingsPlan() {
        return savingsPlan;
    }

    public void setSavingsPlan(SavingsPlan savingsPlan) {
        this.savingsPlan = savingsPlan;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ItemType getType() {
        return type;
    }

    public void setType(ItemType type) {
        this.type = type;
    }

    public Account getAccount() {
        return account;
    }

    public void setAccount(Account account) {
        this.account = account;
    }

    public Float getTargetAmount() {
        return targetAmount != null ? targetAmount : 0.0f;
    }

    public void setTargetAmount(Float targetAmount) {
        this.targetAmount = targetAmount;
    }

    public Float getMonthlyAmount() {
        return monthlyAmount != null ? monthlyAmount : 0.0f;
    }

    public void setMonthlyAmount(Float monthlyAmount) {
        this.monthlyAmount = monthlyAmount;
    }

    public Integer getPriority() {
        return priority != null && priority > 0 ? priority : 1;
    }

    public void setPriority(Integer priority) {
        this.priority = priority != null && priority > 0 ? priority : 1;
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public Float getCurrentBalance() {
        if (account == null) {
            return 0.0f;
        }
        return account.getLatestBalanceValue();
    }

    public Float getAccountLimit() {
        if (account == null) {
            return 0.0f;
        }
        return account.getAvailableLimit();
    }

    /**
     * For a credit account, debt owed is limit - available balance.
     * For a debit account, an overdraft debt is the negative balance amount (-balance).
     * If user explicitly specified targetAmount and no account is attached,
     * targetAmount can represent the debt.
     */
    public Float getDebtOwed() {
        if (account != null && account.getType() == Account.AccountType.CREDIT) {
            float limit = getAccountLimit();
            float balance = getCurrentBalance();
            return round2(Math.max(0.0f, limit - balance));
        } else if (account != null && (account.getType() == Account.AccountType.DEBIT || account.getType() == Account.AccountType.DEBIT_SHARED_BILLS)) {
            float balance = getCurrentBalance();
            if (balance < 0.0f) {
                return round2(-balance);
            }
            if (targetAmount != null && targetAmount > 0.0f) {
                return round2(targetAmount);
            }
            return 0.0f;
        } else if (account != null && account.getType() == Account.AccountType.LOAN) {
            return round2(Math.max(0.0f, getCurrentBalance()));
        } else if (targetAmount != null && targetAmount > 0.0f) {
            return round2(targetAmount);
        }
        return 0.0f;
    }

    public Float getRemainingToTarget() {
        float target = getTargetAmount();
        float current = getCurrentBalance();
        return round2(Math.max(0.0f, target - current));
    }

    /**
     * Calculates rough months to pay off credit debt based on balance and limit.
     * Does not take into account interest or minimum payments (rough estimate).
     *
     * Returns:
     * - 0 if debt is already 0 (paid off)
     * - ceil(debt / monthlyAmount) if debt > 0 and monthlyAmount > 0
     * - null if monthlyAmount is 0 or unassigned
     */
    public Integer getRoughMonthsToPayoff() {
        if (type != ItemType.DEBT_PAYOFF) {
            return null;
        }
        float debt = getDebtOwed();
        if (debt <= 0.0f) {
            return 0;
        }
        if (monthlyAmount == null || monthlyAmount <= 0.0f) {
            return null;
        }
        return (int) Math.ceil((double) debt / (double) monthlyAmount);
    }

    /**
     * Calculates rough months to reach a defined savings target.
     *
     * Returns:
     * - 0 if target is defined and remaining is 0 (achieved)
     * - ceil(remaining / monthlyAmount) if target > 0 and monthlyAmount > 0
     * - null if no target or monthlyAmount is 0
     */
    public Integer getRoughMonthsToTarget() {
        if (type != ItemType.SAVINGS_TARGET) {
            return null;
        }
        float target = getTargetAmount();
        if (target <= 0.0f) {
            return null; // ongoing savings with no hard target
        }
        float remaining = getRemainingToTarget();
        if (remaining <= 0.0f) {
            return 0;
        }
        if (monthlyAmount == null || monthlyAmount <= 0.0f) {
            return null;
        }
        return (int) Math.ceil((double) remaining / (double) monthlyAmount);
    }
}
