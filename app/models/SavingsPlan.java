package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;

@Entity
@Table(name = "savings_plan", indexes = @Index(columnList = "userId"))
public class SavingsPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", nullable = false)
    private String userId;

    private String name = "Monthly Savings Plan";

    private int savingsPercentage = 0;

    private boolean archived = false;

    private Long createdAt;

    private Long lastUpdated;

    @OneToMany(mappedBy = "savingsPlan", cascade = CascadeType.ALL, fetch = FetchType.EAGER, orphanRemoval = true)
    private List<SavingsPlanItem> items = new ArrayList<>();

    public SavingsPlan() {
    }

    public SavingsPlan(String userId, String name, int savingsPercentage) {
        this.userId = userId;
        this.name = (name != null && !name.trim().isEmpty()) ? name.trim() : "Monthly Savings Plan";
        this.savingsPercentage = savingsPercentage;
        this.archived = false;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastUpdated = now;
    }

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getSavingsPercentage() {
        return savingsPercentage;
    }

    public void setSavingsPercentage(int savingsPercentage) {
        this.savingsPercentage = savingsPercentage;
    }

    public boolean isArchived() {
        return archived;
    }

    public void setArchived(boolean archived) {
        this.archived = archived;
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

    public List<SavingsPlanItem> getItems() {
        if (items == null) {
            return new ArrayList<>();
        }
        return items.stream()
                .filter(i -> i != null && !i.isArchived())
                .collect(Collectors.toList());
    }

    public List<SavingsPlanItem> getAllItems() {
        return items;
    }

    public void setItems(List<SavingsPlanItem> items) {
        this.items = items;
    }

    public void addItem(SavingsPlanItem item) {
        if (this.items == null) {
            this.items = new ArrayList<>();
        }
        item.setSavingsPlan(this);
        this.items.add(item);
    }

    public Float getTotalAllocated() {
        double sum = getItems().stream()
                .mapToDouble(i -> i.getMonthlyAmount() != null ? i.getMonthlyAmount() : 0.0)
                .sum();
        return round2((float) sum);
    }

    public Float getTargetAmount(Float incomingTotal) {
        if (incomingTotal == null || incomingTotal <= 0f) {
            return 0.0f;
        }
        return round2((savingsPercentage / 100.0f) * incomingTotal);
    }

    public Float getUnallocatedAmount(Float incomingTotal) {
        float target = getTargetAmount(incomingTotal);
        return round2(target - getTotalAllocated());
    }
}
