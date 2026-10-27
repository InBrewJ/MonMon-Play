package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

import static helpers.TimeHelpers.unixTimestampToDisplayDate;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Pot {
    public enum PotType {
        MONTHLY,
        YEARLY,
        SAVING_TARGET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    @Enumerated(EnumType.STRING)
    public PotType type;

    @Column(nullable = false)
    public Long createdAt;

    @Column(nullable = false)
    public Long lastUpdated;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "pot_accounts",
            joinColumns = @JoinColumn(name = "pot_id"),
            inverseJoinColumns = @JoinColumn(name = "account_id")
    )
    public List<Account> accounts = new ArrayList<>();

    @Column(name = "spending_plan_name")
    public String spendingPlanName;

    @Column(name = "spending_plan_food")
    public Float spendingPlanFood;

    @Column(name = "spending_plan_fuel")
    public Float spendingPlanFuel;

    @Column(name = "spending_plan_transport")
    public Float spendingPlanTransport;

    @Column(name = "spending_plan_fun")
    public Float spendingPlanFun;

    public boolean archived = false;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public PotType getType() {
        return type;
    }

    public void setType(PotType type) {
        this.type = type;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public List<Account> getAccounts() {
        return accounts;
    }

    public void setAccounts(List<Account> accounts) {
        this.accounts = accounts;
    }

    public void addAccount(Account account) {
        if (accounts == null) {
            accounts = new ArrayList<>();
        }
        if (account != null && !accounts.contains(account)) {
            accounts.add(account);
        }
    }

    public boolean hasSpendingPlan() {
        return spendingPlanName != null && !spendingPlanName.trim().isEmpty();
    }

    public String getSpendingPlanName() {
        return spendingPlanName;
    }

    public void setSpendingPlanName(String spendingPlanName) {
        this.spendingPlanName = spendingPlanName;
    }

    public Float getSpendingPlanFood() {
        return spendingPlanFood;
    }

    public void setSpendingPlanFood(Float spendingPlanFood) {
        this.spendingPlanFood = spendingPlanFood;
    }

    public Float getSpendingPlanFuel() {
        return spendingPlanFuel;
    }

    public void setSpendingPlanFuel(Float spendingPlanFuel) {
        this.spendingPlanFuel = spendingPlanFuel;
    }

    public Float getSpendingPlanTransport() {
        return spendingPlanTransport;
    }

    public void setSpendingPlanTransport(Float spendingPlanTransport) {
        this.spendingPlanTransport = spendingPlanTransport;
    }

    public Float getSpendingPlanFun() {
        return spendingPlanFun;
    }

    public void setSpendingPlanFun(Float spendingPlanFun) {
        this.spendingPlanFun = spendingPlanFun;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
    }

    public String getCreatedAtHumanReadable() {
        return unixTimestampToDisplayDate(getCreatedAt());
    }

    public String getLastUpdatedHumanReadable() {
        return unixTimestampToDisplayDate(getLastUpdated());
    }
}
