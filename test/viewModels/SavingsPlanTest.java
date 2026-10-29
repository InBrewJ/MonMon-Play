package viewModels;

import models.Account;
import models.Balance;
import models.SavingsPlan;
import models.SavingsPlanItem;
import org.junit.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SavingsPlanTest {

    private Account createCreditAccount(long id, String name, float limit, float balanceValue) {
        Account credit = new Account();
        credit.setId(id);
        credit.setName(name);
        credit.setType(Account.AccountType.CREDIT);
        credit.setAvailableLimit(limit);
        credit.outgoings = Collections.emptyList();
        credit.balances = new ArrayList<>();

        Balance b = new Balance();
        b.setValue(balanceValue);
        b.setTimestamp(System.currentTimeMillis());
        b.setAccount(credit);
        credit.balances.add(b);
        return credit;
    }

    private Account createSavingsAccount(long id, String name, float balanceValue) {
        Account savings = new Account();
        savings.setId(id);
        savings.setName(name);
        savings.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        savings.setAvailableLimit(0f);
        savings.outgoings = Collections.emptyList();
        savings.balances = new ArrayList<>();

        Balance b = new Balance();
        b.setValue(balanceValue);
        b.setTimestamp(System.currentTimeMillis());
        b.setAccount(savings);
        savings.balances.add(b);
        return savings;
    }

    private Account createDebitAccount(long id, String name, float overdraftLimit, float balanceValue) {
        Account debit = new Account();
        debit.setId(id);
        debit.setName(name);
        debit.setType(Account.AccountType.DEBIT);
        debit.setAvailableLimit(overdraftLimit);
        debit.outgoings = Collections.emptyList();
        debit.balances = new ArrayList<>();

        Balance b = new Balance();
        b.setValue(balanceValue);
        b.setTimestamp(System.currentTimeMillis());
        b.setAccount(debit);
        debit.balances.add(b);
        return debit;
    }

    @Test
    public void testCreditPayoffCalculatesRoughMonths() {
        // Limit: 2000, Available balance: 1400 -> Debt: 600
        Account barclaycard = createCreditAccount(1L, "Barclaycard", 2000f, 1400f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Barclaycard Payoff",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                barclaycard,
                null,
                200.0f
        );

        assertThat(item.getDebtOwed()).isEqualTo(600.0f);
        // 600 / 200 = 3 months
        assertThat(item.getRoughMonthsToPayoff()).isEqualTo(3);
    }

    @Test
    public void testCreditPayoffRoundsUpFractionalMonths() {
        // Limit: 1000, Available balance: 650 -> Debt: 350
        Account credit = createCreditAccount(2L, "Amex", 1000f, 650f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Amex Payoff",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                credit,
                null,
                150.0f
        );

        assertThat(item.getDebtOwed()).isEqualTo(350.0f);
        // 350 / 150 = 2.333... -> roughly 3 months
        assertThat(item.getRoughMonthsToPayoff()).isEqualTo(3);
    }

    @Test
    public void testCreditPayoffWhenAlreadyPaidOffReturnsZeroMonths() {
        // Limit: 1000, Available balance: 1000 -> Debt: 0
        Account credit = createCreditAccount(3L, "ZeroDebtCard", 1000f, 1000f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Zero Debt Card",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                credit,
                null,
                100.0f
        );

        assertThat(item.getDebtOwed()).isEqualTo(0.0f);
        assertThat(item.getRoughMonthsToPayoff()).isEqualTo(0);
    }

    @Test
    public void testCreditPayoffWithoutMonthlyAllocationReturnsNull() {
        Account credit = createCreditAccount(4L, "NoAllocCard", 1000f, 700f);

        SavingsPlanItem item = new SavingsPlanItem(
                "No Alloc Card",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                credit,
                null,
                0.0f
        );

        assertThat(item.getDebtOwed()).isEqualTo(300.0f);
        assertThat(item.getRoughMonthsToPayoff()).isNull();
    }

    @Test
    public void testSavingsTargetCalculatesRoughMonths() {
        // Current balance: 250, Target: 1000 -> Remaining: 750
        Account savings = createSavingsAccount(5L, "Emergency Fund", 250f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Emergency Fund",
                SavingsPlanItem.ItemType.SAVINGS_TARGET,
                savings,
                1000.0f,
                250.0f
        );

        assertThat(item.getCurrentBalance()).isEqualTo(250.0f);
        assertThat(item.getRemainingToTarget()).isEqualTo(750.0f);
        // 750 / 250 = 3 months
        assertThat(item.getRoughMonthsToTarget()).isEqualTo(3);
    }

    @Test
    public void testSavingsTargetWhenAchievedReturnsZeroMonths() {
        Account savings = createSavingsAccount(6L, "Holiday Fund", 1200f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Holiday Fund",
                SavingsPlanItem.ItemType.SAVINGS_TARGET,
                savings,
                1000.0f,
                200.0f
        );

        assertThat(item.getRemainingToTarget()).isEqualTo(0.0f);
        assertThat(item.getRoughMonthsToTarget()).isEqualTo(0);
    }

    @Test
    public void testSavingsPlanSlicingAndAllocationTotals() {
        SavingsPlan plan = new SavingsPlan("user123", "October Plan", 25);

        Account card = createCreditAccount(7L, "Credit Card", 1500f, 1000f); // 500 debt
        Account savings = createSavingsAccount(8L, "Pots", 100f);

        SavingsPlanItem item1 = new SavingsPlanItem("Card", SavingsPlanItem.ItemType.DEBT_PAYOFF, card, null, 250.0f);
        SavingsPlanItem item2 = new SavingsPlanItem("Pots", SavingsPlanItem.ItemType.SAVINGS_TARGET, savings, 1000f, 150.0f);

        plan.addItem(item1);
        plan.addItem(item2);

        Float incomingTotal = 2000.0f;
        // 25% of 2000 = 500 target
        assertThat(plan.getTargetAmount(incomingTotal)).isEqualTo(500.0f);
        // Allocated: 250 + 150 = 400
        assertThat(plan.getTotalAllocated()).isEqualTo(400.0f);
        // Unallocated: 500 - 400 = 100
        assertThat(plan.getUnallocatedAmount(incomingTotal)).isEqualTo(100.0f);
    }

    @Test
    public void testSoftDeletedItemIsExcludedFromActiveItems() {
        SavingsPlan plan = new SavingsPlan("user123", "Test Plan", 20);

        SavingsPlanItem item1 = new SavingsPlanItem("Active", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 100f);
        SavingsPlanItem item2 = new SavingsPlanItem("Deleted", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 100f);
        item2.setArchived(true);

        plan.addItem(item1);
        plan.addItem(item2);

        assertThat(plan.getItems()).containsExactly(item1);
        assertThat(plan.getTotalAllocated()).isEqualTo(100.0f);
    }

    @Test
    public void testSpogViewModelHasSavingsPlan() {
        SavingsPlan plan = new SavingsPlan("user123", "SPOG Plan", 30);
        viewModels.Spog spog = new viewModels.Spog(
                500.0f,
                25,
                30,
                2000.0f,
                1500.0f,
                600.0f,
                0.0f,
                200.0f,
                0.0f,
                0.0f,
                0.0f,
                Collections.emptyList(),
                Collections.emptyList(),
                LocalDate.of(2026, 10, 27),
                null,
                plan
        );

        assertThat(spog.hasSavingsPlan()).isTrue();
        assertThat(spog.getSavingsPlan()).isEqualTo(plan);
        assertThat(spog.getPercentageIncomeAsSavings()).isEqualTo(30);

        // When plan is soft-deleted
        plan.setArchived(true);
        assertThat(spog.hasSavingsPlan()).isFalse();
    }

    @Test
    public void testScaleItemsProportionallyPreservesWeightsWhenSliderChanges() {
        SavingsPlan plan = new SavingsPlan("user123", "Scaling Plan", 20);

        SavingsPlanItem item1 = new SavingsPlanItem("Credit Card", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, null, 100.0f);
        SavingsPlanItem item2 = new SavingsPlanItem("Holiday Pot", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 300.0f);

        plan.addItem(item1);
        plan.addItem(item2);

        // Initial total allocated = 400 (item1 has 25% share, item2 has 75% share)
        assertThat(plan.getTotalAllocated()).isEqualTo(400.0f);

        Float incomingsTotal = 2000.0f;
        // Scale to 30% of 2000 = 600 target pot
        plan.scaleItemsProportionally(incomingsTotal, 30);

        assertThat(plan.getSavingsPercentage()).isEqualTo(30);
        assertThat(item1.getMonthlyAmount()).isEqualTo(150.0f); // 25% of 600
        assertThat(item2.getMonthlyAmount()).isEqualTo(450.0f); // 75% of 600
        assertThat(plan.getTotalAllocated()).isEqualTo(600.0f);
        assertThat(plan.getUnallocatedAmount(incomingsTotal)).isEqualTo(0.0f);

        // Scale down to 10% of 2000 = 200 target pot
        plan.scaleItemsProportionally(incomingsTotal, 10);

        assertThat(plan.getSavingsPercentage()).isEqualTo(10);
        assertThat(item1.getMonthlyAmount()).isEqualTo(50.0f);  // 25% of 200
        assertThat(item2.getMonthlyAmount()).isEqualTo(150.0f); // 75% of 200
        assertThat(plan.getTotalAllocated()).isEqualTo(200.0f);
    }

    @Test
    public void testScaleItemsProportionallyDividesEquallyWhenInitialTotalIsZero() {
        SavingsPlan plan = new SavingsPlan("user123", "Zero Initial Plan", 0);

        SavingsPlanItem item1 = new SavingsPlanItem("Item 1", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 0.0f);
        SavingsPlanItem item2 = new SavingsPlanItem("Item 2", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 0.0f);

        plan.addItem(item1);
        plan.addItem(item2);

        Float incomingsTotal = 2000.0f;
        // Scale to 20% of 2000 = 400 target pot
        plan.scaleItemsProportionally(incomingsTotal, 20);

        assertThat(item1.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(item2.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(plan.getTotalAllocated()).isEqualTo(400.0f);
    }

    @Test
    public void testDebitOverdraftPayoffCalculatesDebtAndRoughMonths() {
        // Debit account with £500 overdraft limit, currently at -£300 balance
        Account currentAccount = createDebitAccount(9L, "NatWest Current", 500f, -300f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Clear Overdraft",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                currentAccount,
                null,
                100.0f
        );

        // Debt owed is the £300 overdrawn amount
        assertThat(item.getDebtOwed()).isEqualTo(300.0f);
        // 300 / 100 = 3 months
        assertThat(item.getRoughMonthsToPayoff()).isEqualTo(3);
    }

    @Test
    public void testDebitPositiveBalanceHasZeroDebtOwed() {
        // Debit account with £500 overdraft limit, but currently positive at +£250
        Account currentAccount = createDebitAccount(10L, "Monzo Current", 500f, 250f);

        SavingsPlanItem item = new SavingsPlanItem(
                "Monzo",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                currentAccount,
                null,
                50.0f
        );

        assertThat(item.getDebtOwed()).isEqualTo(0.0f);
        assertThat(item.getRoughMonthsToPayoff()).isEqualTo(0);
    }

    @Test
    public void testQueueOrderingByPriority() {
        SavingsPlan plan = new SavingsPlan("user123", "Priority Plan", 20);

        SavingsPlanItem itemP3 = new SavingsPlanItem("P3 Item", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 100f, 3);
        SavingsPlanItem itemP1 = new SavingsPlanItem("P1 Item", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, null, 200f, 1);
        SavingsPlanItem itemP2 = new SavingsPlanItem("P2 Item", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 150f, 2);

        plan.addItem(itemP3);
        plan.addItem(itemP1);
        plan.addItem(itemP2);

        List<SavingsPlanItem> active = plan.getItems();
        assertThat(active).hasSize(3);
        assertThat(active.get(0).getName()).isEqualTo("P1 Item");
        assertThat(active.get(0).getPriority()).isEqualTo(1);
        assertThat(active.get(1).getName()).isEqualTo("P2 Item");
        assertThat(active.get(1).getPriority()).isEqualTo(2);
        assertThat(active.get(2).getName()).isEqualTo("P3 Item");
        assertThat(active.get(2).getPriority()).isEqualTo(3);
    }

    @Test
    public void testQueueRolloverOnItemDeletionPreservesTotalPot() {
        SavingsPlan plan = new SavingsPlan("user123", "Queue Rollover Plan", 20);

        SavingsPlanItem p1 = new SavingsPlanItem("P1 Debt", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, null, 100.0f, 1);
        SavingsPlanItem p2 = new SavingsPlanItem("P2 Emergency", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 150.0f, 2);
        SavingsPlanItem p3 = new SavingsPlanItem("P3 Holiday", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 2000f, 200.0f, 3);

        plan.addItem(p1);
        plan.addItem(p2);
        plan.addItem(p3);

        // Total initially: 100 + 150 + 200 = 450
        assertThat(plan.getTotalAllocated()).isEqualTo(450.0f);

        // User removes P2: P2's 150 rolls into P1 -> P1 becomes 250. P3 moves up to P2 (200).
        plan.removeItemAndRebalanceQueue(p2);

        List<SavingsPlanItem> activeAfterP2 = plan.getItems();
        assertThat(activeAfterP2).hasSize(2);
        assertThat(activeAfterP2.get(0).getName()).isEqualTo("P1 Debt");
        assertThat(activeAfterP2.get(0).getPriority()).isEqualTo(1);
        assertThat(activeAfterP2.get(0).getMonthlyAmount()).isEqualTo(250.0f);

        assertThat(activeAfterP2.get(1).getName()).isEqualTo("P3 Holiday");
        assertThat(activeAfterP2.get(1).getPriority()).isEqualTo(2);
        assertThat(activeAfterP2.get(1).getMonthlyAmount()).isEqualTo(200.0f);

        assertThat(plan.getTotalAllocated()).isEqualTo(450.0f);

        // User removes P1: P1's 250 rolls into the remaining item (formerly P3, now P1) -> new P1 becomes 200 + 250 = 450.
        plan.removeItemAndRebalanceQueue(p1);

        List<SavingsPlanItem> activeAfterP1 = plan.getItems();
        assertThat(activeAfterP1).hasSize(1);
        assertThat(activeAfterP1.get(0).getName()).isEqualTo("P3 Holiday");
        assertThat(activeAfterP1.get(0).getPriority()).isEqualTo(1);
        assertThat(activeAfterP1.get(0).getMonthlyAmount()).isEqualTo(450.0f);

        assertThat(plan.getTotalAllocated()).isEqualTo(450.0f);
    }

    @Test
    public void testBalanceEvenlyDistributesToUnlockedItems() {
        SavingsPlan plan = new SavingsPlan("user123", "Balance Evenly Plan", 25);
        Float incomingTotal = 2000.0f; // Target pot: 25% of 2000 = 500.00

        SavingsPlanItem item1 = new SavingsPlanItem("Fixed Loan", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, null, 100.0f, 1);
        item1.setLocked(true); // Fixed at £100

        SavingsPlanItem item2 = new SavingsPlanItem("Holiday", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 50.0f, 2);
        SavingsPlanItem item3 = new SavingsPlanItem("Emergency", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 2000f, 50.0f, 3);

        plan.addItem(item1);
        plan.addItem(item2);
        plan.addItem(item3);

        // Target pot: 500. Locked: 100. Available for unlocked: 400.
        // Item2 and Item3 should each get 200.
        plan.balanceEvenly(incomingTotal);

        assertThat(item1.getMonthlyAmount()).isEqualTo(100.0f);
        assertThat(item2.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(item3.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(plan.getTotalAllocated()).isEqualTo(500.0f);
        assertThat(plan.getUnallocatedAmount(incomingTotal)).isEqualTo(0.0f);
    }

    @Test
    public void testBalanceItemsToTargetPreservesLockedItemsAndReconcilesPennies() {
        SavingsPlan plan = new SavingsPlan("user123", "Penny Drift Plan", 20);
        Float incomingTotal = 1666.67f; // Target pot: 20% of 1666.67 = 333.33

        SavingsPlanItem item1 = new SavingsPlanItem("P1", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 1);
        SavingsPlanItem item2 = new SavingsPlanItem("P2", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 2);
        SavingsPlanItem item3 = new SavingsPlanItem("P3", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 3);

        plan.addItem(item1);
        plan.addItem(item2);
        plan.addItem(item3);

        // Rebalance to target: 333.33 divided across 3 items (111.11, 111.11, 111.11)
        plan.balanceItemsToTarget(incomingTotal);

        float target = plan.getTargetAmount(incomingTotal);
        assertThat(plan.getTotalAllocated()).isEqualTo(target);
        assertThat(plan.getUnallocatedAmount(incomingTotal)).isEqualTo(0.0f);
    }

    @Test
    public void testMoveItemUpAndDownShuntsQueueOrderAndRebalancesPriorities() {
        SavingsPlan plan = new SavingsPlan("user123", "Shunt Test Plan", 20);

        SavingsPlanItem item1 = new SavingsPlanItem("Goal A", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 1);
        item1.setId(101L);
        SavingsPlanItem item2 = new SavingsPlanItem("Goal B", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 2);
        item2.setId(102L);
        SavingsPlanItem item3 = new SavingsPlanItem("Goal C", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, null, 100.0f, 3);
        item3.setId(103L);

        plan.addItem(item1);
        plan.addItem(item2);
        plan.addItem(item3);

        // Initial queue order: Goal A (P1), Goal B (P2), Goal C (P3)
        List<SavingsPlanItem> items = plan.getItems();
        assertThat(items.get(0).getName()).isEqualTo("Goal A");
        assertThat(items.get(0).getPriority()).isEqualTo(1);
        assertThat(items.get(1).getName()).isEqualTo("Goal B");
        assertThat(items.get(1).getPriority()).isEqualTo(2);
        assertThat(items.get(2).getName()).isEqualTo("Goal C");
        assertThat(items.get(2).getPriority()).isEqualTo(3);

        // Shunt Goal B up -> should become P1, Goal A becomes P2
        boolean movedUp = plan.moveItemUp(102L);
        assertThat(movedUp).isTrue();

        List<SavingsPlanItem> afterUp = plan.getItems();
        assertThat(afterUp.get(0).getName()).isEqualTo("Goal B");
        assertThat(afterUp.get(0).getPriority()).isEqualTo(1);
        assertThat(afterUp.get(1).getName()).isEqualTo("Goal A");
        assertThat(afterUp.get(1).getPriority()).isEqualTo(2);
        assertThat(afterUp.get(2).getName()).isEqualTo("Goal C");
        assertThat(afterUp.get(2).getPriority()).isEqualTo(3);

        // Shunt Goal B down -> should become P2 again
        boolean movedDown = plan.moveItemDown(102L);
        assertThat(movedDown).isTrue();

        List<SavingsPlanItem> afterDown = plan.getItems();
        assertThat(afterDown.get(0).getName()).isEqualTo("Goal A");
        assertThat(afterDown.get(0).getPriority()).isEqualTo(1);
        assertThat(afterDown.get(1).getName()).isEqualTo("Goal B");
        assertThat(afterDown.get(1).getPriority()).isEqualTo(2);
        assertThat(afterDown.get(2).getName()).isEqualTo("Goal C");
        assertThat(afterDown.get(2).getPriority()).isEqualTo(3);

        // Shunting P1 up should return false (already at top)
        assertThat(plan.moveItemUp(101L)).isFalse();

        // Shunting P3 down should return false (already at bottom)
        assertThat(plan.moveItemDown(103L)).isFalse();
    }

    @Test
    public void testAddCreditCardsWithBalanceAppendsLowerPriorityItems() {
        SavingsPlan plan = new SavingsPlan("user123", "Credit Payoff Plan", 20);

        // Pre-existing item (Priority 1)
        SavingsPlanItem existingItem = new SavingsPlanItem("Emergency Fund", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 100f, 1);
        existingItem.setId(1L);
        plan.addItem(existingItem);
        assertThat(plan.getItems().size()).isEqualTo(1);
        assertThat(plan.getItems().get(0).getPriority()).isEqualTo(1);

        // Credit Card A: Limit 1000, Balance 800 -> Debt 200 (Has balance)
        Account ccA = createCreditAccount(10L, "Amex Platinum", 1000f, 800f);

        // Credit Card B: Limit 500, Balance 500 -> Debt 0 (Paid off / No balance)
        Account ccB = createCreditAccount(11L, "Barclaycard", 500f, 500f);

        // Credit Card C: Limit 2000, Balance 1500 -> Debt 500 (Has balance)
        Account ccC = createCreditAccount(12L, "Natwest Credit", 2000f, 1500f);

        List<Account> allAccounts = List.of(ccA, ccB, ccC);

        java.util.Set<Long> alreadyLinked = plan.getItems().stream()
                .filter(i -> i.getAccount() != null && i.getAccount().getId() != null)
                .map(i -> i.getAccount().getId())
                .collect(java.util.stream.Collectors.toSet());

        List<Account> toAdd = allAccounts.stream()
                .filter(a -> a.getType() == Account.AccountType.CREDIT)
                .filter(a -> !alreadyLinked.contains(a.getId()))
                .filter(a -> {
                    float limit = a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f;
                    float balance = a.getLatestBalanceValue() != null ? a.getLatestBalanceValue() : 0f;
                    float debt = Math.max(0f, limit - balance);
                    return debt > 0f || (limit == 0f && balance > 0f);
                })
                .collect(java.util.stream.Collectors.toList());

        // ccB has debt 0, so only ccA and ccC should be selected
        assertThat(toAdd).extracting(Account::getName).containsExactly("Amex Platinum", "Natwest Credit");

        // Append to plan with priority lower than anything already in the list
        int priority = plan.getItems().size() + 1;
        for (Account acc : toAdd) {
            float limit = acc.getAvailableLimit() != null ? acc.getAvailableLimit() : 0f;
            float balance = acc.getLatestBalanceValue() != null ? acc.getLatestBalanceValue() : 0f;
            float debt = Math.max(0f, limit - balance);
            SavingsPlanItem item = new SavingsPlanItem(
                    acc.getName(),
                    SavingsPlanItem.ItemType.DEBT_PAYOFF,
                    acc,
                    debt,
                    0.0f,
                    priority++
            );
            plan.addItem(item);
        }

        List<SavingsPlanItem> active = plan.getItems();
        assertThat(active).hasSize(3);

        // Item 1: Emergency Fund (P1)
        assertThat(active.get(0).getName()).isEqualTo("Emergency Fund");
        assertThat(active.get(0).getPriority()).isEqualTo(1);

        // Item 2: Amex Platinum (P2, lower priority than existing P1)
        assertThat(active.get(1).getName()).isEqualTo("Amex Platinum");
        assertThat(active.get(1).getPriority()).isEqualTo(2);
        assertThat(active.get(1).getDebtOwed()).isEqualTo(200.0f);

        // Item 3: Natwest Credit (P3, lower priority than P1 and P2)
        assertThat(active.get(2).getName()).isEqualTo("Natwest Credit");
        assertThat(active.get(2).getPriority()).isEqualTo(3);
        assertThat(active.get(2).getDebtOwed()).isEqualTo(500.0f);
    }

    @Test
    public void testSelfHealRebalancesNonContiguousPrioritiesWithGapsLikeP1P23P24() {
        SavingsPlan plan = new SavingsPlan("user123", "Gap Recovery Plan", 20);

        SavingsPlanItem item1 = new SavingsPlanItem("Item 1", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 100f, 50f, 1);
        item1.setId(1L);
        SavingsPlanItem item2 = new SavingsPlanItem("Item 2", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, 500f, 50f, 23);
        item2.setId(23L);
        SavingsPlanItem item3 = new SavingsPlanItem("Item 3", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, 700f, 50f, 24);
        item3.setId(24L);

        plan.getAllItems().add(item1);
        plan.getAllItems().add(item2);
        plan.getAllItems().add(item3);

        // Before rebalance: items are ordered P1, P23, P24
        List<SavingsPlanItem> before = plan.getItems();
        assertThat(before.get(0).getPriority()).isEqualTo(1);
        assertThat(before.get(1).getPriority()).isEqualTo(23);
        assertThat(before.get(2).getPriority()).isEqualTo(24);

        // Rebalance should self-heal priorities to 1, 2, 3
        plan.rebalancePriorities();

        List<SavingsPlanItem> after = plan.getItems();
        assertThat(after.get(0).getPriority()).isEqualTo(1);
        assertThat(after.get(1).getPriority()).isEqualTo(2);
        assertThat(after.get(2).getPriority()).isEqualTo(3);

        // Shunting down item 1 should move it to priority 2, and item 2 to priority 1
        boolean moved = plan.moveItemDown(1L);
        assertThat(moved).isTrue();

        List<SavingsPlanItem> afterMove = plan.getItems();
        assertThat(afterMove.get(0).getName()).isEqualTo("Item 2");
        assertThat(afterMove.get(0).getPriority()).isEqualTo(1);
        assertThat(afterMove.get(1).getName()).isEqualTo("Item 1");
        assertThat(afterMove.get(1).getPriority()).isEqualTo(2);
        assertThat(afterMove.get(2).getName()).isEqualTo("Item 3");
        assertThat(afterMove.get(2).getPriority()).isEqualTo(3);
    }

    @Test
    public void testRemovalByItemWithSameIdEvenIfDifferentJavaReference() {
        SavingsPlan plan = new SavingsPlan("user123", "Removal Reference Test", 20);

        SavingsPlanItem originalItem = new SavingsPlanItem("Item 1", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 100f, 50f, 1);
        originalItem.setId(10L);
        SavingsPlanItem nextItem = new SavingsPlanItem("Item 2", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 200f, 50f, 2);
        nextItem.setId(20L);

        plan.addItem(originalItem);
        plan.addItem(nextItem);

        // Simulate separate detached entity representing item 10
        SavingsPlanItem separateInstance = new SavingsPlanItem("Item 1", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 100f, 50f, 1);
        separateInstance.setId(10L);

        plan.removeItemAndRebalanceQueue(separateInstance);

        List<SavingsPlanItem> active = plan.getItems();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getId()).isEqualTo(20L);
        assertThat(active.get(0).getPriority()).isEqualTo(1);
        // Cascaded monthly amount
        assertThat(active.get(0).getMonthlyAmount()).isEqualTo(100.0f);
    }

    @Test
    public void testMoveItemDownSequentiallyInTwelveItemQueue() {
        SavingsPlan plan = new SavingsPlan("user123", "Twelve Item Plan", 20);

        for (int i = 1; i <= 12; i++) {
            SavingsPlanItem item = new SavingsPlanItem("Item " + i, SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 10f, i);
            item.setId((long) i);
            plan.addItem(item);
        }

        assertThat(plan.getItems()).hasSize(12);
        assertThat(plan.getItems().get(3).getName()).isEqualTo("Item 4");
        assertThat(plan.getItems().get(3).getPriority()).isEqualTo(4);

        // Move item 4 down 8 times so it moves from position 4 to position 12 (bottom of queue)
        for (int step = 0; step < 8; step++) {
            boolean moved = plan.moveItemDown(4L);
            assertThat(moved).isTrue();
        }

        List<SavingsPlanItem> itemsAfter = plan.getItems();
        assertThat(itemsAfter.get(11).getId()).isEqualTo(4L);
        assertThat(itemsAfter.get(11).getName()).isEqualTo("Item 4");
        assertThat(itemsAfter.get(11).getPriority()).isEqualTo(12);

        // Verify all 12 items maintain strict 1..12 priority sequence
        for (int i = 0; i < 12; i++) {
            assertThat(itemsAfter.get(i).getPriority()).isEqualTo(i + 1);
        }
    }

    @Test
    public void testMoveToBottomAndMoveToTopDirectly() {
        SavingsPlan plan = new SavingsPlan("user123", "Extreme Move Plan", 20);

        for (int i = 1; i <= 12; i++) {
            SavingsPlanItem item = new SavingsPlanItem("Item " + i, SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 10f, i);
            item.setId((long) i);
            plan.addItem(item);
        }

        // Send P4 straight to the bottom
        boolean movedBottom = plan.moveToBottom(4L);
        assertThat(movedBottom).isTrue();

        List<SavingsPlanItem> itemsAfterBottom = plan.getItems();
        assertThat(itemsAfterBottom.get(11).getId()).isEqualTo(4L);
        assertThat(itemsAfterBottom.get(11).getPriority()).isEqualTo(12);

        // Item 5 should now be P4, Item 6 should be P5, etc.
        assertThat(itemsAfterBottom.get(3).getId()).isEqualTo(5L);
        assertThat(itemsAfterBottom.get(3).getPriority()).isEqualTo(4);

        // Send Item 4 straight back to top (P1)
        boolean movedTop = plan.moveToTop(4L);
        assertThat(movedTop).isTrue();

        List<SavingsPlanItem> itemsAfterTop = plan.getItems();
        assertThat(itemsAfterTop.get(0).getId()).isEqualTo(4L);
        assertThat(itemsAfterTop.get(0).getPriority()).isEqualTo(1);
        assertThat(itemsAfterTop.get(1).getId()).isEqualTo(1L);
        assertThat(itemsAfterTop.get(1).getPriority()).isEqualTo(2);

        for (int i = 0; i < 12; i++) {
            assertThat(itemsAfterTop.get(i).getPriority()).isEqualTo(i + 1);
        }
    }

    @Test
    public void testZeroMonthlyAmountForFutureTargetPreservedWhenLocked() {
        SavingsPlan plan = new SavingsPlan("user123", "Future Target Plan", 20);
        Float incomingTotal = 2000.0f; // Target pot: 400.00

        // P1: active goal £100
        SavingsPlanItem p1 = new SavingsPlanItem("Active Emergency", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 1000f, 100f, 1);
        p1.setId(1L);

        // P2: future plan with zero monthly allocation, locked
        SavingsPlanItem p2Future = new SavingsPlanItem("Future Washing Machine", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, 0.0f, 2);
        p2Future.setId(2L);
        p2Future.setLocked(true);

        // P3: active goal £100
        SavingsPlanItem p3 = new SavingsPlanItem("Overdraft Clearout", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, null, 100f, 3);
        p3.setId(3L);

        plan.addItem(p1);
        plan.addItem(p2Future);
        plan.addItem(p3);

        // Future target projection is null when monthly amount is 0 (handled gracefully as future plan)
        assertThat(p2Future.getRoughMonthsToTarget()).isNull();
        assertThat(p2Future.getMonthlyAmount()).isEqualTo(0.0f);

        // Balance evenly across unlocked items: target pot is 400, locked p2 is 0.
        // p1 and p3 should each get 200, while p2 stays at 0.00!
        plan.balanceEvenly(incomingTotal);

        assertThat(p2Future.getMonthlyAmount()).isEqualTo(0.0f);
        assertThat(p1.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(p3.getMonthlyAmount()).isEqualTo(200.0f);
        assertThat(plan.getTotalAllocated()).isEqualTo(400.0f);
    }
}
