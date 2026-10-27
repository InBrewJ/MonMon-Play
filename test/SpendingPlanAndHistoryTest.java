package viewModels;

import helpers.HistoricSpentCalculator;
import models.Account;
import models.Balance;
import models.Pot;
import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SpendingPlanAndHistoryTest {
    private static final ZoneId UK = ZoneId.of("Europe/London");

    private static Balance bal(Account a, float value, LocalDate day, int hour) {
        Balance b = new Balance();
        b.setValue(value);
        b.setTimestamp(day.atTime(hour, 0).atZone(UK).toEpochSecond());
        b.setAccount(a);
        return b;
    }

    private static Account debit(long id, String name) {
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        a.setType(Account.AccountType.DEBIT);
        a.setAvailableLimit(0f);
        a.outgoings = Collections.emptyList();
        a.balances = new ArrayList<>();
        return a;
    }

    @Test
    public void gruellingPlanUsesUkAverageFoodAndLeavesRestForFun() {
        SpendingPlan sp = SpendingPlan.createGruelling(1000f, 0.0, 30);
        assertThat(sp.getPlannedFoodSpend()).isEqualTo(SpendingPlan.UK_AVG_MONTHLY_FOOD_SPEND);
        assertThat(sp.getPlannedFuelSpend()).isEqualTo(0f);
        assertThat(sp.getPlannedTransportSpend()).isEqualTo(0f);
        assertThat(sp.getPlannedFun()).isEqualTo(783.33f);
        assertThat(sp.getWeeklyFoodSpend()).isEqualTo(50.0f);
    }

    @Test
    public void adjustedFunProRatesRemainingEssentials() {
        // 300 allowance, 100 spent, 15 of 30 days left -> half of 200 essentials still to cover
        SpendingPlan sp = new SpendingPlan("Custom", 150f, 30f, 20f, null, 0f, 0.0, 0)
                .withContext(300f, 100.0, 15, 30);
        assertThat(sp.getAdjustedRemainingAllowance()).isEqualTo(200f);
        assertThat(sp.getRemainingEssentials()).isEqualTo(100f);
        assertThat(sp.getAdjustedFun()).isEqualTo(100f);
        assertThat(sp.getAdjustedFunLeftPerDay()).isEqualTo(6.67);
        assertThat(sp.isDeficit()).isFalse();

        SpendingPlan broke = sp.withContext(300f, 280.0, 15, 30);
        assertThat(broke.isDeficit()).isTrue();
        assertThat(broke.getAbsoluteDeficit()).isEqualTo(80f);
        assertThat(broke.getAdjustedFunLeftPerDay()).isEqualTo(0.0);
    }

    @Test
    public void spogUsesSuppliedLocalDateNotJvmDefault() {
        // Payday is the 15th. At 00:30 BST on the 15th the JVM (UTC) would still think it's the 14th.
        LocalDate ukToday = LocalDate.of(2026, 7, 15); // Wednesday, a working day
        Account a = debit(1L, "Current");
        List<Account> accs = Collections.singletonList(a);
        Spog spog = new Spog(600f, 15, 0, 2000f, 1400f, 0f, 0f, 0f, 0f, 0f, 0f, accs, accs, ukToday, null);
        assertThat(spog.getToday()).isEqualTo(ukToday);
        assertThat(spog.getDaysSinceLastPayday()).isEqualTo(0);
        assertThat(spog.isPayday()).isTrue();
        assertThat(spog.getNextPayDate()).isEqualTo(LocalDate.of(2026, 8, 14)); // 15 Aug is Saturday
        assertThat(spog.getDangerDay()).isEqualTo("11th"); // 14 - 3 = 11th

        Spog dayBefore = new Spog(600f, 15, 0, 2000f, 1400f, 0f, 0f, 0f, 0f, 0f, 0f, accs, accs, ukToday.minusDays(1), null);
        assertThat(dayBefore.getDaysUntilNextPayday()).isEqualTo(1);
        assertThat(dayBefore.isPayday()).isFalse();
    }

    @Test
    public void historyUsesSoftDeletedPotConfigsPerCycle() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1; // 1 Oct 2026 Thu, 1 Sep 2026 Tue
        Account oldAcc = debit(1L, "Old Current");
        Account newAcc = debit(2L, "New Current");

        // September cycle: old account spends 100
        oldAcc.balances.add(bal(oldAcc, 1000f, LocalDate.of(2026, 8, 31), 12));
        oldAcc.balances.add(bal(oldAcc, 900f, LocalDate.of(2026, 9, 10), 12));
        // October cycle: old account spends another 50 (should be ignored - pot changed)
        oldAcc.balances.add(bal(oldAcc, 850f, LocalDate.of(2026, 10, 10), 12));
        // October cycle: new account spends 40
        newAcc.balances.add(bal(newAcc, 500f, LocalDate.of(2026, 10, 1), 9));
        newAcc.balances.add(bal(newAcc, 460f, LocalDate.of(2026, 10, 5), 9));

        long sep1 = LocalDate.of(2026, 9, 1).atStartOfDay(UK).toEpochSecond();
        long oct1 = LocalDate.of(2026, 10, 1).atStartOfDay(UK).toEpochSecond();

        Pot oldPot = new Pot();
        oldPot.setType(Pot.PotType.MONTHLY);
        oldPot.setCreatedAt(sep1 - 86400 * 40L);
        oldPot.setLastUpdated(oct1 - 3600); // archived just before October cycle
        oldPot.setArchived(true);
        oldPot.addAccount(oldAcc);

        Pot newPot = new Pot();
        newPot.setType(Pot.PotType.MONTHLY);
        newPot.setCreatedAt(oct1 - 3600);
        newPot.setLastUpdated(oct1 - 3600);
        newPot.addAccount(newAcc);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(oldPot, newPot), List.of(oldAcc, newAcc), payday, today, UK, 500f);

        HistoricCycleSpent current = vm.getCurrentCycle();
        assertThat(current).isNotNull();
        assertThat(current.getCycleStartDate()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(current.getTotalSpent()).isEqualTo(40.0);
        assertThat(current.getStartingPotValue()).isEqualTo(500.0);
        assertThat(current.getEndingPotValue()).isEqualTo(460.0);
        assertThat(current.getMonthlyBudget()).isEqualTo(500.0f);
        assertThat(current.getBudgetDifference()).isEqualTo(460.0f);
        assertThat(current.isOverspent()).isFalse();
        assertThat(current.getPotConfig()).isSameAs(newPot);

        HistoricCycleSpent sep = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(LocalDate.of(2026, 9, 1)))
                .findFirst().orElseThrow();
        assertThat(sep.getTotalSpent()).isEqualTo(100.0);
        assertThat(sep.getStartingPotValue()).isEqualTo(1000.0);
        assertThat(sep.getEndingPotValue()).isEqualTo(900.0);
        assertThat(sep.getMonthlyBudget()).isEqualTo(1000.0f);
        assertThat(sep.getBudgetDifference()).isEqualTo(900.0f);
        assertThat(sep.getPotConfig()).isSameAs(oldPot);
        assertThat(sep.isOverspent()).isFalse();
    }

    @Test
    public void buildInfoLoadsPropertiesOrDefaults() {
        assertThat(helpers.BuildInfo.getBuildNumber()).isNotEmpty();
        assertThat(helpers.BuildInfo.getBuildTimestamp()).isNotEmpty();
        assertThat(helpers.BuildInfo.getBuildCommit()).isNotEmpty();
    }

    @Test
    public void categoryAccountTrackingCalculatesExactRemainingAndFun() {
        Account foodAcc = debit(10L, "Groceries Card");
        Account funAcc = debit(20L, "Monzo Fun");

        SpendingPlan sp = new SpendingPlan("Custom", 200f, 0f, 0f, 100f, 300f, 100.0, 15)
                .withCategoryTracking(foodAcc, null, null, funAcc, 70.0, 0.0, 0.0, 30.0)
                .withContext(300f, 100.0, 15, 30);

        assertThat(sp.hasAssignedFoodAccount()).isTrue();
        assertThat(sp.hasAssignedFunAccount()).isTrue();
        assertThat(sp.hasAssignedFuelAccount()).isFalse();
        assertThat(sp.getFoodAccountName()).isEqualTo("Groceries Card");
        assertThat(sp.getFunAccountName()).isEqualTo("Monzo Fun");

        // Exact remaining: £200 - £70 = £130 left for food (not pro-rated 15/30 = £100)
        assertThat(sp.getActualFoodSpent()).isEqualTo(70.0);
        assertThat(sp.getRemainingFood()).isEqualTo(130.0f);

        // Exact fun remaining: £100 planned - £30 actual spent = £70
        assertThat(sp.getActualFunSpent()).isEqualTo(30.0);
        assertThat(sp.getAdjustedFun()).isEqualTo(70.0f);
        assertThat(sp.isDeficit()).isFalse();

        // Daily food pace for the remaining 15 days: £130 / 15 = £8.67/day
        assertThat(sp.getFoodLeftPerDay()).isEqualTo(8.67);
        // Daily fun pace: £70 / 15 = £4.67/day
        assertThat(sp.getAdjustedFunLeftPerDay()).isEqualTo(4.67);
    }
}
