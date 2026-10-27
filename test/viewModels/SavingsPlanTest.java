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
}
