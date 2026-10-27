package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.Comparator;
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
                .sorted(Comparator.comparing(SavingsPlanItem::getPriority, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SavingsPlanItem::getId, Comparator.nullsLast(Comparator.naturalOrder())))
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
        if (item.getPriority() == null || item.getPriority() <= 0) {
            item.setPriority(getItems().size() + 1);
        }
        this.items.add(item);
    }

    /**
     * Rebalances priorities of active items so they form a clean 1..N sequence
     * in the current queue order.
     */
    public void rebalancePriorities() {
        List<SavingsPlanItem> active = getItems();
        for (int i = 0; i < active.size(); i++) {
            active.get(i).setPriority(i + 1);
        }
    }

    /**
     * Removes an item from the savings plan and rolls its monthly allocation into the
     * top priority item (or remaining queue head), preserving the overall savings pot.
     * Re-indexes remaining items sequentially (1, 2, 3...) so next items move up in the queue.
     */
    public void removeItemAndRebalanceQueue(SavingsPlanItem itemToRemove) {
        if (itemToRemove == null || items == null) {
            return;
        }
        List<SavingsPlanItem> active = getItems();
        if (!active.contains(itemToRemove)) {
            return;
        }

        float amountToRollOver = itemToRemove.getMonthlyAmount() != null ? itemToRemove.getMonthlyAmount() : 0.0f;
        itemToRemove.setArchived(true);
        itemToRemove.setMonthlyAmount(0.0f);

        List<SavingsPlanItem> remaining = getItems();
        if (!remaining.isEmpty()) {
            SavingsPlanItem topItem = remaining.get(0);
            float currentTop = topItem.getMonthlyAmount() != null ? topItem.getMonthlyAmount() : 0.0f;
            topItem.setMonthlyAmount(round2(currentTop + amountToRollOver));

            for (int i = 0; i < remaining.size(); i++) {
                remaining.get(i).setPriority(i + 1);
            }
        }
        this.lastUpdated = System.currentTimeMillis();
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

    /**
     * Scales all active items proportionally to match a new savings target percentage.
     * Preserves original proportions across all items.
     */
    public void scaleItemsProportionally(Float incomingTotal, int newPercentage) {
        float oldTotalAllocated = getTotalAllocated();
        this.savingsPercentage = newPercentage;
        this.lastUpdated = System.currentTimeMillis();

        if (incomingTotal == null || incomingTotal <= 0f) {
            return;
        }

        float newTargetPot = getTargetAmount(incomingTotal);
        List<SavingsPlanItem> activeItems = getItems();
        if (activeItems.isEmpty()) {
            return;
        }

        if (oldTotalAllocated <= 0f) {
            float equalShare = round2(newTargetPot / activeItems.size());
            for (SavingsPlanItem item : activeItems) {
                item.setMonthlyAmount(equalShare);
            }
            return;
        }

        for (SavingsPlanItem item : activeItems) {
            float oldAmount = item.getMonthlyAmount() != null ? item.getMonthlyAmount() : 0.0f;
            float weight = oldAmount / oldTotalAllocated;
            float newAmount = round2(newTargetPot * weight);
            item.setMonthlyAmount(newAmount);
        }
    }
}
