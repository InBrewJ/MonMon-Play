package viewModels;

import helpers.HistoricSpentCalculator;
import helpers.ModelHelpers;
import models.Account;
import models.Balance;
import models.Incoming;
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

        // September cycle: old account spends 100 (1000 -> 900)
        oldAcc.balances.add(bal(oldAcc, 1000f, LocalDate.of(2026, 8, 31), 12));
        oldAcc.balances.add(bal(oldAcc, 900f, LocalDate.of(2026, 9, 10), 12));
        // October cycle: old account spends another 50 (900 -> 850)
        oldAcc.balances.add(bal(oldAcc, 850f, LocalDate.of(2026, 10, 10), 12));
        // October cycle: new account spends 40 (500 -> 460)
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
        // September cycle: newAcc did not exist yet, oldAcc spent 100
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

    @Test
    public void historicSpentTracksNetDeltasAcrossAccounts() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;
        Account acc = debit(100L, "Main Current");

        // Payday balance on Oct 1: 1000
        acc.balances.add(bal(acc, 1000f, LocalDate.of(2026, 10, 1), 9));
        // Oct 5: Spent 200 (balance drops to 800) -> delta = -200 (spend +200)
        acc.balances.add(bal(acc, 800f, LocalDate.of(2026, 10, 5), 12));
        // Oct 10: Inflow of +500 (balance goes up to 1300) -> delta = +500 (offsets spend by -500)
        acc.balances.add(bal(acc, 1300f, LocalDate.of(2026, 10, 10), 12));
        // Oct 15: Spent 300 (balance drops to 1000) -> delta = -300 (spend +300)
        acc.balances.add(bal(acc, 1000f, LocalDate.of(2026, 10, 15), 14));
        // Oct 20: Spent 150 (balance drops to 850) -> delta = -150 (spend +150)
        acc.balances.add(bal(acc, 850f, LocalDate.of(2026, 10, 20), 14));

        long oct1Sec = LocalDate.of(2026, 10, 1).atStartOfDay(UK).toEpochSecond();
        long oct12Sec = LocalDate.of(2026, 10, 12).atStartOfDay(UK).toEpochSecond();

        Pot potA = new Pot();
        potA.setType(Pot.PotType.MONTHLY);
        potA.setCreatedAt(oct1Sec);
        potA.setLastUpdated(oct12Sec);
        potA.setArchived(true);
        potA.addAccount(acc);

        Pot potB = new Pot();
        potB.setType(Pot.PotType.MONTHLY);
        potB.setCreatedAt(oct12Sec);
        potB.setLastUpdated(oct12Sec);
        potB.setArchived(false);
        potB.addAccount(acc);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(potA, potB), List.of(acc), payday, today, UK, 1000f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();
        assertThat(cycle.getPotConfig()).isSameAs(potB);

        // Net spent: +200 - 500 + 300 + 150 = 150.0
        assertThat(cycle.getTotalSpent()).isEqualTo(150.0);
    }

    @Test
    public void currentToSavingsTransferNetsToZeroSpend() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        Account savings = new Account();
        savings.setId(2L);
        savings.setName("Instant Access Savings");
        savings.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        savings.setAvailableLimit(0f);
        savings.outgoings = Collections.emptyList();
        savings.balances = new ArrayList<>();

        // Payday start balances on Oct 1
        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 10, 1), 9));
        savings.balances.add(bal(savings, 2000f, LocalDate.of(2026, 10, 1), 9));

        // Oct 10: Transfer £400 from Current to Savings
        current.balances.add(bal(current, 600f, LocalDate.of(2026, 10, 10), 11)); // -400
        savings.balances.add(bal(savings, 2400f, LocalDate.of(2026, 10, 10), 11)); // +400

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current, savings), payday, today, UK, 1000f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // Across accounts, the transfer nets to 0 spend
        assertThat(cycle.getTotalSpent()).isEqualTo(0.0);
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(3000.0);
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(3000.0);
    }

    @Test
    public void spendingFromSavingsIsCapturedAsSpend() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        Account savings = new Account();
        savings.setId(2L);
        savings.setName("Holiday Savings");
        savings.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        savings.setAvailableLimit(0f);
        savings.outgoings = Collections.emptyList();
        savings.balances = new ArrayList<>();

        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 10, 1), 9));
        savings.balances.add(bal(savings, 3000f, LocalDate.of(2026, 10, 1), 9));

        // Oct 12: Pay £600 for flights directly out of savings
        savings.balances.add(bal(savings, 2400f, LocalDate.of(2026, 10, 12), 15));

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current, savings), payday, today, UK, 1000f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // 600 spend out of savings is captured
        assertThat(cycle.getTotalSpent()).isEqualTo(600.0);
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(4000.0);
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(3400.0);
    }

    @Test
    public void creditCardSpendAndPayoffFromCurrent() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        Account credit = new Account();
        credit.setId(2L);
        credit.setName("Barclaycard");
        credit.setType(Account.AccountType.CREDIT);
        credit.setAvailableLimit(2000f);
        credit.outgoings = Collections.emptyList();
        credit.balances = new ArrayList<>();

        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 10, 1), 9));
        credit.balances.add(bal(credit, 1500f, LocalDate.of(2026, 10, 1), 9)); // 500 owed

        // Oct 5: Spend 150 on credit card (available limit drops from 1500 to 1350)
        credit.balances.add(bal(credit, 1350f, LocalDate.of(2026, 10, 5), 14));

        HistoricSpentViewModel vm1 = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current, credit), payday, today, UK, 1000f);
        assertThat(vm1.getCurrentCycle().getTotalSpent()).isEqualTo(150.0);

        // Oct 15: Pay off 150 from Current to Credit Card
        // Current drops by 150 (1000 -> 850), Credit available limit rises by 150 (1350 -> 1500)
        current.balances.add(bal(current, 850f, LocalDate.of(2026, 10, 15), 10));
        credit.balances.add(bal(credit, 1500f, LocalDate.of(2026, 10, 15), 10));

        HistoricSpentViewModel vm2 = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current, credit), payday, today, UK, 1000f);
        // The payoff netted to 0, so total spend remains 150.0
        assertThat(vm2.getCurrentCycle().getTotalSpent()).isEqualTo(150.0);
    }

    @Test
    public void pensionAndLoanAccountsAreExcludedFromLedger() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        Account pension = new Account();
        pension.setId(2L);
        pension.setName("Aviva SIPP");
        pension.setType(Account.AccountType.PENSION);
        pension.setAvailableLimit(0f);
        pension.outgoings = Collections.emptyList();
        pension.balances = new ArrayList<>();

        Account loan = new Account();
        loan.setId(3L);
        loan.setName("Bank Loan");
        loan.setType(Account.AccountType.LOAN);
        loan.setAvailableLimit(10000f);
        loan.outgoings = Collections.emptyList();
        loan.balances = new ArrayList<>();

        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 10, 1), 9));
        current.balances.add(bal(current, 800f, LocalDate.of(2026, 10, 5), 12)); // Spent 200

        // Pension grows by 10,000 due to market
        pension.balances.add(bal(pension, 50000f, LocalDate.of(2026, 10, 1), 9));
        pension.balances.add(bal(pension, 60000f, LocalDate.of(2026, 10, 20), 9));

        // Loan balance decreases by 500
        loan.balances.add(bal(loan, 5000f, LocalDate.of(2026, 10, 1), 9));
        loan.balances.add(bal(loan, 4500f, LocalDate.of(2026, 10, 20), 9));

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current, pension, loan), payday, today, UK, 1000f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        // Pension and loan are excluded, so spend is strictly current account's 200.0
        assertThat(cycle.getTotalSpent()).isEqualTo(200.0);
        assertThat(cycle.getAccountBreakdowns()).hasSize(1);
        assertThat(cycle.getAccountBreakdowns().get(0).getAccountName()).isEqualTo("Main Current");
    }

    @Test
    public void incomingWorkRateChipCalculation() {
        Incoming inc = new Incoming();
        // £4,583.333/month * 12 = £55,000 / year
        inc.setGrossValue(4583.333f);
        inc.setNetValue(3200f);

        assertThat(inc.getDailyRate()).isNotNull();
        // 55,000 / 253 = 217.39
        assertThat(String.format("%.2f", inc.getDailyRate())).isEqualTo("217.39");
        // 217.39 / 7.5 = 28.99
        assertThat(String.format("%.2f", inc.getHourlyRate())).isEqualTo("28.99");
        assertThat(inc.getWorkRateChip()).isEqualTo("[£217.39/day for 7.5 hours £28.99/hr]");
    }

    @Test
    public void paydayIncomingAssumedIntoTargetAccountWhenNoPaydayBalanceEntered() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        // Balance on Sept 30 (day before payday): £100
        current.balances.add(bal(current, 100f, LocalDate.of(2026, 9, 30), 18));

        // User does NOT enter a balance on Oct 1 payday.
        // User spends £400 by Oct 5 (recorded balance £2,200)
        current.balances.add(bal(current, 2200f, LocalDate.of(2026, 10, 5), 12));
        // User spends further by Oct 20 (recorded balance £700)
        current.balances.add(bal(current, 700f, LocalDate.of(2026, 10, 20), 12));

        Incoming salary = new Incoming();
        salary.setId(10L);
        salary.setName("Employer Salary");
        salary.setNetValue(2500f);
        salary.setIncomingMonthDay(1);
        salary.setPayDay(true);
        salary.setAccount(current);
        salary.setCreatedAt(LocalDate.of(2026, 8, 1).atStartOfDay(UK).toEpochSecond());

        Pot pot = new Pot();
        pot.setType(Pot.PotType.MONTHLY);
        pot.setCreatedAt(LocalDate.of(2026, 9, 1).atStartOfDay(UK).toEpochSecond());
        pot.addAccount(current);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(pot), List.of(current), List.of(salary), payday, today, UK, 500f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // Starting total balance should assume the £2,500 salary landed on Oct 1: 100 + 2500 = 2600.0
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(2600.0);
        // Ending balance is 700.0
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(700.0);
        // Total spent across cycle should be: 2600.0 - 700.0 = 1900.0
        assertThat(cycle.getTotalSpent()).isEqualTo(1900.0);
        // The monthly budget derived from the pot should also be 2600.0
        assertThat(cycle.getMonthlyBudget()).isEqualTo(2600.0f);
    }

    @Test
    public void paydayIncomingNotDoubleCountedIfUserEntersPaydayBalance() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        // Balance on Sept 30: £100
        current.balances.add(bal(current, 100f, LocalDate.of(2026, 9, 30), 18));
        // User logged in on Oct 1 at 09:00 and explicitly recorded £2,600 (already including salary)
        current.balances.add(bal(current, 2600f, LocalDate.of(2026, 10, 1), 9));
        // End of month balance: £600
        current.balances.add(bal(current, 600f, LocalDate.of(2026, 10, 25), 12));

        Incoming salary = new Incoming();
        salary.setId(10L);
        salary.setName("Employer Salary");
        salary.setNetValue(2500f);
        salary.setIncomingMonthDay(1);
        salary.setPayDay(true);
        salary.setAccount(current);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current), List.of(salary), payday, today, UK, 500f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        // Since Oct 1 snapshot exists, it is NOT double-counted: starting balance stays 2600.0
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(2600.0);
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(600.0);
        assertThat(cycle.getTotalSpent()).isEqualTo(2000.0);
    }

    @Test
    public void historicAndSoftDeletedIncomingsHonoredInTheirActiveCycles() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        // Balance before Sept cycle (Aug 31): £200
        current.balances.add(bal(current, 200f, LocalDate.of(2026, 8, 31), 18));
        // End of Sept cycle (Sept 30): £500
        current.balances.add(bal(current, 500f, LocalDate.of(2026, 9, 30), 18));
        // End of Oct cycle (Oct 25): £800
        current.balances.add(bal(current, 800f, LocalDate.of(2026, 10, 25), 18));

        // Old salary: active in Sept cycle, but archived on Sept 25 before Oct cycle
        Incoming oldSalary = new Incoming();
        oldSalary.setId(1L);
        oldSalary.setName("Old Job Salary");
        oldSalary.setNetValue(2000f);
        oldSalary.setPayDay(true);
        oldSalary.setIncomingMonthDay(1);
        oldSalary.setAccount(current);
        oldSalary.setCreatedAt(LocalDate.of(2026, 1, 1).atStartOfDay(UK).toEpochSecond());
        oldSalary.setArchived(true);
        // Archived on Sept 25, so it was active on Sept 1 cycle start, but dead by Oct 1
        oldSalary.setArchivedAt(LocalDate.of(2026, 9, 25).atStartOfDay(UK).toEpochSecond());

        // New salary: created Sept 26, active for Oct cycle
        Incoming newSalary = new Incoming();
        newSalary.setId(2L);
        newSalary.setName("New Job Salary");
        newSalary.setNetValue(3000f);
        newSalary.setPayDay(true);
        newSalary.setIncomingMonthDay(1);
        newSalary.setAccount(current);
        newSalary.setCreatedAt(LocalDate.of(2026, 9, 26).atStartOfDay(UK).toEpochSecond());
        newSalary.setArchived(false);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(), List.of(current), List.of(oldSalary, newSalary), payday, today, UK, 500f);

        // Current cycle (Oct 1 - Nov 1): uses newSalary (+3000)
        HistoricCycleSpent octCycle = vm.getCurrentCycle();
        assertThat(octCycle.getStartingTotalBalance()).isEqualTo(3500.0); // 500 + 3000

        // Previous cycle (Sept 1 - Oct 1): uses oldSalary (+2000)
        List<HistoricCycleSpent> cycles = vm.getCycles();
        HistoricCycleSpent septCycle = cycles.stream()
                .filter(c -> c.getCycleStartDate().equals(LocalDate.of(2026, 9, 1)))
                .findFirst().orElse(null);
        assertThat(septCycle).isNotNull();
        assertThat(septCycle.getStartingTotalBalance()).isEqualTo(2200.0); // 200 + 2000
    }

    @Test
    public void arbitraryBudgetHeadingsCanHaveNSectionsWithLeftoverForFun() {
        List<models.SpendingPlanSection> sections = List.of(
                new models.SpendingPlanSection("Food", 250f, null),
                new models.SpendingPlanSection("Fuel", 60f, null),
                new models.SpendingPlanSection("Gym", 40f, null),
                new models.SpendingPlanSection("Subscriptions", 30f, null),
                new models.SpendingPlanSection("Childcare", 120f, null)
        );

        // Max allowance 800, 0 spent, 30 days left
        SpendingPlan sp = new SpendingPlan("Family Mode", sections, null, 800f, 0.0, 30);

        assertThat(sp.hasCustomSections()).isTrue();
        assertThat(sp.getSections()).hasSize(5);
        // Total essentials: 250 + 60 + 40 + 30 + 120 = 500
        assertThat(sp.getTotalPlannedEssentials()).isEqualTo(500.0f);
        // Leftover for fun: 800 - 500 = 300
        assertThat(sp.getPlannedFun()).isEqualTo(300.0f);
    }

    @Test
    public void potSerializesAndDeserializesCustomSections() {
        Pot pot = new Pot();
        pot.setSpendingPlanName("Custom N-Sections Plan");

        List<models.SpendingPlanSection> sections = List.of(
                new models.SpendingPlanSection("Groceries", 200f, 10L),
                new models.SpendingPlanSection("Commute", 80f, 20L),
                new models.SpendingPlanSection("Coffee & Treats", 50f, null)
        );
        pot.setSpendingSections(sections);

        List<models.SpendingPlanSection> recovered = pot.getSpendingSections();
        assertThat(recovered).hasSize(3);
        assertThat(recovered.get(0).getHeading()).isEqualTo("Groceries");
        assertThat(recovered.get(0).getAmount()).isEqualTo(200f);
        assertThat(recovered.get(0).getAccountId()).isEqualTo(10L);

        assertThat(recovered.get(1).getHeading()).isEqualTo("Commute");
        assertThat(recovered.get(1).getAmount()).isEqualTo(80f);

        assertThat(recovered.get(2).getHeading()).isEqualTo("Coffee & Treats");
        assertThat(recovered.get(2).getAmount()).isEqualTo(50f);
    }

    @Test
    public void customSpendingPlanStoresAndRetrievesSections() {
        models.CustomSpendingPlan plan = new models.CustomSpendingPlan("user123", "Student Budget", List.of(
                new models.SpendingPlanSection("Food", 150f, null),
                new models.SpendingPlanSection("Books", 30f, null),
                new models.SpendingPlanSection("Going Out", 70f, null)
        ));

        assertThat(plan.getName()).isEqualTo("Student Budget");
        assertThat(plan.getSections()).hasSize(3);
        assertThat(plan.getTotalSectionsAmount()).isEqualTo(250.0f);
    }

    @Test
    public void historicSpentShowsMonthlyIncomeAndIgnoresTransfersToSavings() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(10L, "Current Account");
        Account savings = new Account();
        savings.setId(20L);
        savings.setName("High Interest Savings");
        savings.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        savings.setAvailableLimit(0f);
        savings.outgoings = Collections.emptyList();
        savings.balances = new ArrayList<>();

        // Start balances on Oct 1
        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 10, 1), 9));
        savings.balances.add(bal(savings, 2000f, LocalDate.of(2026, 10, 1), 9));

        // Oct 5: Spent £300 on groceries/bills from Current
        current.balances.add(bal(current, 700f, LocalDate.of(2026, 10, 5), 12));

        // Oct 10: Transferred £400 from Current into Savings
        current.balances.add(bal(current, 300f, LocalDate.of(2026, 10, 10), 14)); // drops to 300
        savings.balances.add(bal(savings, 2400f, LocalDate.of(2026, 10, 10), 14)); // rises to 2400

        Incoming salary = new Incoming();
        salary.setId(100L);
        salary.setName("Monthly Salary");
        salary.setNetValue(2500f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(1);
        salary.setAccount(current);
        salary.setCreatedAt(LocalDate.of(2026, 1, 1).atStartOfDay(UK).toEpochSecond());

        Pot pot = new Pot();
        pot.setType(Pot.PotType.MONTHLY);
        pot.setCreatedAt(LocalDate.of(2026, 10, 1).atStartOfDay(UK).toEpochSecond());
        pot.addAccount(current);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(pot), List.of(current, savings), List.of(salary), payday, today, UK, 500f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // Income for this month should be £2,500
        assertThat(cycle.getMonthlyIncome()).isEqualTo(2500.0);

        // Total spent across the month should be £300 (the £400 transfer to savings is completely ignored)
        assertThat(cycle.getTotalSpent()).isEqualTo(300.0);

        // Pot spent should also ignore the £400 savings transfer
        assertThat(cycle.getPotSpent()).isEqualTo(300.0);

        // Pot allowance is £500
        assertThat(cycle.getPotAllowance()).isEqualTo(500.0f);

        // £300 spent vs £500 allowance -> not overspent, £200 under allowance
        assertThat(cycle.isOverPotAllowance()).isFalse();
        assertThat(cycle.getAbsolutePotOverspend()).isEqualTo(200.0);
    }

    @Test
    public void multipleMonthlyPotsInPeriodUsesLastPotAndCapturesDeletionSnapshot() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account currentA = debit(101L, "Current A");
        Account currentB = debit(102L, "Current B");

        currentA.balances.add(bal(currentA, 1000f, LocalDate.of(2026, 10, 1), 9));
        currentB.balances.add(bal(currentB, 1000f, LocalDate.of(2026, 10, 1), 9));

        // Oct 5: Spent £150 on Current A
        currentA.balances.add(bal(currentA, 850f, LocalDate.of(2026, 10, 5), 12));

        // Oct 15: Pot 1 is deleted/archived and replaced with Pot 2
        long oct1Sec = LocalDate.of(2026, 10, 1).atStartOfDay(UK).toEpochSecond();
        long oct15Sec = LocalDate.of(2026, 10, 15).atStartOfDay(UK).toEpochSecond();

        Pot pot1 = new Pot();
        pot1.setId(1L);
        pot1.setType(Pot.PotType.MONTHLY);
        pot1.setSpendingPlanName("First Pot");
        pot1.setCreatedAt(oct1Sec);
        pot1.setLastUpdated(oct15Sec);
        pot1.setArchived(true);
        pot1.addAccount(currentA);

        Pot pot2 = new Pot();
        pot2.setId(2L);
        pot2.setType(Pot.PotType.MONTHLY);
        pot2.setSpendingPlanName("Final Reconfigured Pot");
        pot2.setCreatedAt(oct15Sec);
        pot2.setLastUpdated(oct15Sec);
        pot2.setArchived(false);
        pot2.addAccount(currentB);

        // Oct 20: Spent £400 on Current B
        currentB.balances.add(bal(currentB, 600f, LocalDate.of(2026, 10, 20), 12));

        Incoming salary = new Incoming();
        salary.setId(200L);
        salary.setName("Monthly Salary");
        salary.setNetValue(2000f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(1);
        salary.setAccount(currentB);
        salary.setCreatedAt(LocalDate.of(2026, 1, 1).atStartOfDay(UK).toEpochSecond());

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(pot1, pot2), List.of(currentA, currentB), List.of(salary), payday, today, UK, 350f);

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // 1. Pot used for cycle configuration must be the LAST pot (pot2)
        assertThat(cycle.getPotConfig()).isSameAs(pot2);

        // 2. Pot spent is derived from the last pot accounts (Current B: £400)
        assertThat(cycle.getPotSpent()).isEqualTo(400.0);

        // 3. Pot allowance is £350
        assertThat(cycle.getPotAllowance()).isEqualTo(350.0f);

        // 4. Over allowance by £50 (£400 spent vs £350 allowance)
        assertThat(cycle.isOverPotAllowance()).isTrue();
        assertThat(cycle.getAbsolutePotOverspend()).isEqualTo(50.0);
        assertThat(cycle.getPotOverspend()).isEqualTo(50.0);

        // 5. State of all monthly pots configured in this period
        assertThat(cycle.hasMultiplePots()).isTrue();
        List<HistoricPotSnapshot> snapshots = cycle.getPotsInPeriod();
        assertThat(snapshots).hasSize(2);

        // Snapshot of Pot 1 (archived on Oct 15)
        HistoricPotSnapshot snap1 = snapshots.stream().filter(s -> s.getPot().getId().equals(1L)).findFirst().orElseThrow();
        assertThat(snap1.isFirstPot()).isTrue();
        assertThat(snap1.isLastPot()).isFalse();
        assertThat(snap1.isArchivedInPeriod()).isTrue();
        assertThat(snap1.getArchivedAt()).isEqualTo(oct15Sec);
        assertThat(snap1.getAccountSnapshots()).hasSize(1);
        // Balance on the day the pot was deleted was 850.0, and 150.0 was spent
        assertThat(snap1.getAccountSnapshots().get(0).getAccountName()).isEqualTo("Current A");
        assertThat(snap1.getAccountSnapshots().get(0).getBalanceOnDate()).isEqualTo(850.0);
        assertThat(snap1.getAccountSnapshots().get(0).getAmountSpent()).isEqualTo(150.0);

        // Snapshot of Pot 2 (last pot)
        HistoricPotSnapshot snap2 = snapshots.stream().filter(s -> s.getPot().getId().equals(2L)).findFirst().orElseThrow();
        assertThat(snap2.isFirstPot()).isFalse();
        assertThat(snap2.isLastPot()).isTrue();
        assertThat(snap2.isArchivedInPeriod()).isFalse();
        assertThat(snap2.getAccountSnapshots()).hasSize(1);
        assertThat(snap2.getAccountSnapshots().get(0).getAccountName()).isEqualTo("Current B");
        assertThat(snap2.getAccountSnapshots().get(0).getBalanceOnDate()).isEqualTo(600.0);
        assertThat(snap2.getAccountSnapshots().get(0).getAmountSpent()).isEqualTo(400.0);
    }

    @Test
    public void fourMonthlyPotsInPeriodDisplaysFirstIntermediaryAndLastActivePots() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account acc = debit(100L, "Main Current");
        // Sept 1: 2000
        acc.balances.add(bal(acc, 2000f, LocalDate.of(2026, 9, 1), 9));
        // Oct 1: 1500
        acc.balances.add(bal(acc, 1500f, LocalDate.of(2026, 10, 1), 9));
        // Oct 25: 1100
        acc.balances.add(bal(acc, 1100f, LocalDate.of(2026, 10, 25), 9));

        long sep1 = LocalDate.of(2026, 9, 1).atStartOfDay(UK).toEpochSecond();
        long oct1 = LocalDate.of(2026, 10, 1).atStartOfDay(UK).toEpochSecond();
        long oct8 = LocalDate.of(2026, 10, 8).atStartOfDay(UK).toEpochSecond();
        long oct15 = LocalDate.of(2026, 10, 15).atStartOfDay(UK).toEpochSecond();
        long oct22 = LocalDate.of(2026, 10, 22).atStartOfDay(UK).toEpochSecond();

        Pot pot0 = new Pot();
        pot0.setId(10L);
        pot0.setType(Pot.PotType.MONTHLY);
        pot0.setSpendingPlanName("September Pot");
        pot0.setCreatedAt(sep1);
        pot0.setLastUpdated(oct1);
        pot0.setArchived(true);
        pot0.addAccount(acc);

        Pot pot1 = new Pot();
        pot1.setId(11L);
        pot1.setType(Pot.PotType.MONTHLY);
        pot1.setSpendingPlanName("Oct Week 1 Pot");
        pot1.setCreatedAt(oct1);
        pot1.setLastUpdated(oct8);
        pot1.setArchived(true);
        pot1.addAccount(acc);

        Pot pot2 = new Pot();
        pot2.setId(12L);
        pot2.setType(Pot.PotType.MONTHLY);
        pot2.setSpendingPlanName("Oct Week 2 Pot");
        pot2.setCreatedAt(oct8);
        pot2.setLastUpdated(oct15);
        pot2.setArchived(true);
        pot2.addAccount(acc);

        Pot pot3 = new Pot();
        pot3.setId(13L);
        pot3.setType(Pot.PotType.MONTHLY);
        pot3.setSpendingPlanName("Oct Week 3 Pot");
        pot3.setCreatedAt(oct15);
        pot3.setLastUpdated(oct22);
        pot3.setArchived(true);
        pot3.addAccount(acc);

        Pot pot4 = new Pot();
        pot4.setId(14L);
        pot4.setType(Pot.PotType.MONTHLY);
        pot4.setSpendingPlanName("Oct Final Pot");
        pot4.setCreatedAt(oct22);
        pot4.setLastUpdated(oct22);
        pot4.setArchived(false);
        pot4.addAccount(acc);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                List.of(pot0, pot1, pot2, pot3, pot4),
                List.of(acc),
                Collections.emptyList(),
                payday,
                today,
                UK,
                500f
        );

        // Find October cycle
        HistoricCycleSpent octCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(LocalDate.of(2026, 10, 1)))
                .findFirst().orElseThrow();

        // All 4 pots configured during October are displayed in order: first, intermediary, last
        List<HistoricPotSnapshot> octSnaps = octCycle.getPotsInPeriod();
        assertThat(octSnaps).hasSize(4);

        // First active pot (pot1)
        assertThat(octSnaps.get(0).getPot().getId()).isEqualTo(11L);
        assertThat(octSnaps.get(0).isFirstPot()).isTrue();
        assertThat(octSnaps.get(0).isLastPot()).isFalse();
        assertThat(octSnaps.get(0).isIntermediaryPot()).isFalse();

        // Intermediary pot 1 (pot2)
        assertThat(octSnaps.get(1).getPot().getId()).isEqualTo(12L);
        assertThat(octSnaps.get(1).isFirstPot()).isFalse();
        assertThat(octSnaps.get(1).isLastPot()).isFalse();
        assertThat(octSnaps.get(1).isIntermediaryPot()).isTrue();

        // Intermediary pot 2 (pot3)
        assertThat(octSnaps.get(2).getPot().getId()).isEqualTo(13L);
        assertThat(octSnaps.get(2).isFirstPot()).isFalse();
        assertThat(octSnaps.get(2).isLastPot()).isFalse();
        assertThat(octSnaps.get(2).isIntermediaryPot()).isTrue();

        // Last active pot (pot4) - used for cycle configuration
        assertThat(octSnaps.get(3).getPot().getId()).isEqualTo(14L);
        assertThat(octSnaps.get(3).isFirstPot()).isFalse();
        assertThat(octSnaps.get(3).isLastPot()).isTrue();
        assertThat(octSnaps.get(3).isIntermediaryPot()).isFalse();

        // pot0 (ended before Oct) must NOT appear in October snapshots
        assertThat(octSnaps.stream().noneMatch(s -> s.getPot().getId().equals(10L))).isTrue();

        // September cycle must ONLY display pot0, and NONE of pot1, pot2, pot3, pot4
        HistoricCycleSpent sepCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(LocalDate.of(2026, 9, 1)))
                .findFirst().orElseThrow();
        List<HistoricPotSnapshot> sepSnaps = sepCycle.getPotsInPeriod();
        assertThat(sepSnaps).hasSize(1);
        assertThat(sepSnaps.get(0).getPot().getId()).isEqualTo(10L);
        assertThat(sepSnaps.get(0).isFirstPot()).isTrue();
        assertThat(sepSnaps.get(0).isLastPot()).isTrue();
        assertThat(sepSnaps.get(0).isIntermediaryPot()).isFalse();
    }

    @Test
    public void softDeletedAccountsAreExcludedFromAccountListUnderEachHeadingOnHistoricSpent() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account activeAcc = debit(1L, "Active Current");
        activeAcc.balances.add(bal(activeAcc, 1000f, LocalDate.of(2026, 10, 1), 9));
        activeAcc.balances.add(bal(activeAcc, 800f, LocalDate.of(2026, 10, 15), 9)); // Spent 200

        Account archivedAcc = debit(2L, "Closed Old Account");
        archivedAcc.setArchived(true);
        archivedAcc.balances.add(bal(archivedAcc, 500f, LocalDate.of(2026, 10, 1), 9));
        archivedAcc.balances.add(bal(archivedAcc, 500f, LocalDate.of(2026, 10, 15), 9));

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(),
                List.of(activeAcc, archivedAcc),
                Collections.emptyList(),
                payday,
                today,
                UK,
                500f
        );

        HistoricCycleSpent cycle = vm.getCurrentCycle();
        assertThat(cycle).isNotNull();

        // In the Account list under this heading, only non-soft-deleted accounts are shown
        List<AccountSpentItem> breakdowns = cycle.getAccountBreakdowns();
        assertThat(breakdowns).hasSize(1);
        assertThat(breakdowns.get(0).getAccountName()).isEqualTo("Active Current");

        // The soft-deleted account must NOT be present
        assertThat(breakdowns.stream().noneMatch(b -> "Closed Old Account".equals(b.getAccountName()))).isTrue();

        // Total balances and spend reflect only the non-soft-deleted accounts
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(1000.0);
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(800.0);
        assertThat(cycle.getTotalSpent()).isEqualTo(200.0);
    }

    @Test
    public void incomingRecordsEditHistoryInJsonStructure() {
        Incoming inc = new Incoming();
        inc.setId(10L);
        inc.setName("Initial Contract");
        inc.setNetValue(2000f);
        inc.setGrossValue(2500f);
        inc.setIncomingMonthDay(1);
        inc.setPayDay(true);
        inc.setType("CONTRACT");

        // Record first edit snapshot
        long t1 = LocalDate.of(2026, 8, 15).atStartOfDay(UK).toEpochSecond();
        inc.recordEditHistorySnapshot(t1);

        // Update fields
        inc.setName("Contract Raise");
        inc.setNetValue(2400f);
        inc.setGrossValue(3000f);

        // Record second edit snapshot
        long t2 = LocalDate.of(2026, 9, 20).atStartOfDay(UK).toEpochSecond();
        inc.recordEditHistorySnapshot(t2);

        // Update to final values
        inc.setName("Occasio Insights");
        inc.setNetValue(2642.83f);
        inc.setGrossValue(3300f);

        // Verify JSON string is populated
        assertThat(inc.getHistoryJson()).isNotNull();
        assertThat(inc.getHistoryJson()).contains("Initial Contract");
        assertThat(inc.getHistoryJson()).contains("2000.0");
        assertThat(inc.getHistoryJson()).contains("Contract Raise");
        assertThat(inc.getHistoryJson()).contains("2400.0");

        // Verify deserialized history entries
        List<Incoming.IncomingHistoryEntry> history = inc.getHistory();
        assertThat(history).hasSize(2);

        Incoming.IncomingHistoryEntry h1 = history.get(0);
        assertThat(h1.getTimestamp()).isEqualTo(t1);
        assertThat(h1.getName()).isEqualTo("Initial Contract");
        assertThat(h1.getNetValue()).isEqualTo(2000f);

        Incoming.IncomingHistoryEntry h2 = history.get(1);
        assertThat(h2.getTimestamp()).isEqualTo(t2);
        assertThat(h2.getName()).isEqualTo("Contract Raise");
        assertThat(h2.getNetValue()).isEqualTo(2400f);

        // Current values on the object
        assertThat(inc.getName()).isEqualTo("Occasio Insights");
        assertThat(inc.getNetValue()).isEqualTo(2642.83f);
    }

    @Test
    public void singleIncomingEditHistoryHonoredAcrossHistoricCycles() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        int payday = 1;

        Account current = debit(1L, "Main Current");
        // Start of Aug: 1000
        current.balances.add(bal(current, 1000f, LocalDate.of(2026, 8, 1), 9));
        // Start of Sept: 1200
        current.balances.add(bal(current, 1200f, LocalDate.of(2026, 9, 1), 9));
        // Start of Oct: 1500
        current.balances.add(bal(current, 1500f, LocalDate.of(2026, 10, 1), 9));
        // Oct 27: 1100
        current.balances.add(bal(current, 1100f, LocalDate.of(2026, 10, 27), 9));

        Incoming salary = new Incoming();
        salary.setId(100L);
        salary.setName("Initial Contract");
        salary.setNetValue(2000f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(1);
        salary.setAccount(current);
        salary.setCreatedAt(LocalDate.of(2026, 7, 1).atStartOfDay(UK).toEpochSecond());

        // Snapshot recorded on Aug 20 (raised from 2000 to 2400)
        long aug20Sec = LocalDate.of(2026, 8, 20).atStartOfDay(UK).toEpochSecond();
        salary.recordEditHistorySnapshot(aug20Sec);
        salary.setName("Contract Raise");
        salary.setNetValue(2400f);

        // Snapshot recorded on Sept 25 (raised from 2400 to 2642.83)
        long sep25Sec = LocalDate.of(2026, 9, 25).atStartOfDay(UK).toEpochSecond();
        salary.recordEditHistorySnapshot(sep25Sec);
        salary.setName("Occasio Insights");
        salary.setNetValue(2642.83f);

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(),
                List.of(current),
                List.of(salary),
                payday,
                today,
                UK,
                500f
        );

        // 1. Current cycle (Oct 1 - Nov 1): uses latest edit value (£2642.83)
        HistoricCycleSpent octCycle = vm.getCurrentCycle();
        assertThat(octCycle.getMonthlyIncome()).isEqualTo(2642.83);

        // 2. September cycle (Sept 1 - Oct 1): uses the value active during Sept (£2400.0)
        HistoricCycleSpent sepCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(LocalDate.of(2026, 9, 1)))
                .findFirst().orElseThrow();
        assertThat(sepCycle.getMonthlyIncome()).isEqualTo(2400.0);

        // 3. August cycle (starts on last working day before Aug 1 -> 31 July 2026): uses the value active during Aug (£2000.0)
        LocalDate augCycleStart = ModelHelpers.getActualPaydayDate(payday, 2026, 8);
        HistoricCycleSpent augCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(augCycleStart))
                .findFirst().orElseThrow();
        assertThat(augCycle.getMonthlyIncome()).isEqualTo(2000.0);
    }

    @Test
    public void softDeletedIncomingsDoNotInflateCurrentCycleIncomeEvenWithMillisecondTimestamps() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        int payday = 30; // 30 Oct 2026 cycle start

        Account current = debit(1L, "Main Current");
        current.balances.add(bal(current, 4000f, LocalDate.of(2026, 10, 30), 9));
        current.balances.add(bal(current, 3850f, LocalDate.of(2026, 10, 31), 9));

        // Active incoming
        Incoming activeSalary = new Incoming();
        activeSalary.setId(1L);
        activeSalary.setName("Occasio Insights");
        activeSalary.setNetValue(2642.83f);
        activeSalary.setPayDay(true);
        activeSalary.setIncomingMonthDay(30);
        activeSalary.setAccount(current);
        activeSalary.setArchived(false);

        // Old soft-deleted incoming from previous job (£4,168) with 13-digit millisecond timestamp
        Incoming oldJob = new Incoming();
        oldJob.setId(2L);
        oldJob.setName("Old Job Salary");
        oldJob.setNetValue(4168.00f);
        oldJob.setPayDay(true);
        oldJob.setIncomingMonthDay(30);
        oldJob.setAccount(current);
        oldJob.setArchived(true);
        // Stored as milliseconds (e.g. System.currentTimeMillis())
        oldJob.setArchivedAt(System.currentTimeMillis());

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(),
                List.of(current),
                List.of(activeSalary, oldJob),
                payday,
                today,
                UK,
                500f
        );

        HistoricCycleSpent currentCycle = vm.getCurrentCycle();
        assertThat(currentCycle).isNotNull();

        // Income must strictly be £2,642.83 and NOT inflated to £6,810.83!
        assertThat(currentCycle.getMonthlyIncome()).isEqualTo(2642.83);
    }

    @Test
    public void pastCyclesUseArchivedSalaryAndDoNotAddCurrentSalaryWhenCreatedAtIsNull() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        int payday = 30;

        Account current = debit(1L, "Main Current");
        // Start of Aug cycle (28 Aug 2026): 1000
        LocalDate aug28 = ModelHelpers.getActualPaydayDate(payday, 2026, 8); // Friday 28 Aug 2026
        LocalDate sep30 = ModelHelpers.getActualPaydayDate(payday, 2026, 9); // Wednesday 30 Sep 2026
        LocalDate oct30 = ModelHelpers.getActualPaydayDate(payday, 2026, 10); // Friday 30 Oct 2026

        current.balances.add(bal(current, 1000f, aug28, 9));
        current.balances.add(bal(current, 1200f, sep30, 9));
        current.balances.add(bal(current, 1500f, oct30, 9));
        current.balances.add(bal(current, 1400f, today, 9));

        // Archived salary: £4,168.00 (archived on Oct 30, 2026 13:00) with createdAt == null
        Incoming oldJob = new Incoming();
        oldJob.setId(1L);
        oldJob.setName("Old Job Salary");
        oldJob.setNetValue(4168.00f);
        oldJob.setPayDay(true);
        oldJob.setIncomingMonthDay(30);
        oldJob.setAccount(current);
        oldJob.setArchived(true);
        oldJob.setCreatedAt(null); // legacy row created before createdAt column existed
        oldJob.setArchivedAt(oct30.atTime(13, 0).atZone(UK).toEpochSecond());

        // Active salary: Occasio Insights £2,642.83 with createdAt == null
        Incoming activeSalary = new Incoming();
        activeSalary.setId(2L);
        activeSalary.setName("Occasio Insights");
        activeSalary.setNetValue(2642.83f);
        activeSalary.setPayDay(true);
        activeSalary.setIncomingMonthDay(30);
        activeSalary.setAccount(current);
        activeSalary.setArchived(false);
        activeSalary.setCreatedAt(null); // legacy row or null

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(),
                List.of(current),
                List.of(activeSalary, oldJob),
                payday,
                today,
                UK,
                500f
        );

        // 1. Current cycle (30 Oct 2026 - 30 Nov 2026): uses active salary (£2642.83)
        HistoricCycleSpent currentCycle = vm.getCurrentCycle();
        assertThat(currentCycle).isNotNull();
        assertThat(currentCycle.getCycleStartDate()).isEqualTo(oct30);
        assertThat(currentCycle.getMonthlyIncome()).isEqualTo(2642.83);

        // 2. September cycle (30 Sep 2026 - 30 Oct 2026): uses old salary (£4168.00) and NOT (£6810.83)
        HistoricCycleSpent sepCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(sep30))
                .findFirst().orElseThrow();
        assertThat(sepCycle.getMonthlyIncome()).isEqualTo(4168.00);

        // 3. August cycle (28 Aug 2026 - 30 Sep 2026): uses old salary (£4168.00) and NOT (£6810.83)
        HistoricCycleSpent augCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(aug28))
                .findFirst().orElseThrow();
        assertThat(augCycle.getMonthlyIncome()).isEqualTo(4168.00);
    }

    @Test
    public void salaryTimelineAxiomHonorsHistoricEditAndPrecedingJobTransition() {
        LocalDate today = LocalDate.of(2026, 10, 31);
        int payday = 30;

        Account current = debit(1L, "Main Current");
        LocalDate aug28_26 = ModelHelpers.getActualPaydayDate(payday, 2026, 8); // 28 Aug 2026
        LocalDate sep30_26 = ModelHelpers.getActualPaydayDate(payday, 2026, 9); // 30 Sep 2026
        LocalDate oct30_26 = ModelHelpers.getActualPaydayDate(payday, 2026, 10); // 30 Oct 2026
        LocalDate may30_25 = ModelHelpers.getActualPaydayDate(payday, 2025, 5); // 30 May 2025

        current.balances.add(bal(current, 800f, may30_25, 9));
        current.balances.add(bal(current, 1000f, aug28_26, 9));
        current.balances.add(bal(current, 1200f, sep30_26, 9));
        current.balances.add(bal(current, 1500f, oct30_26, 9));
        current.balances.add(bal(current, 1400f, today, 9));

        // Axiom: 4,168.00 salary ended before June 2025
        long june2025Sec = LocalDate.of(2025, 6, 1).atStartOfDay(UK).toEpochSecond();
        Incoming oldJob = new Incoming();
        oldJob.setId(1L);
        oldJob.setName("Old Job Salary");
        oldJob.setNetValue(4168.00f);
        oldJob.setPayDay(true);
        oldJob.setIncomingMonthDay(30);
        oldJob.setAccount(current);
        oldJob.setArchived(true);
        oldJob.setArchivedAt(june2025Sec);

        // Axiom: Occasio Insights started in June 2025 at 3111.96 until October 2026, then 2642.83
        long oct29_26Sec = LocalDate.of(2026, 10, 29).atTime(12, 0).atZone(UK).toEpochSecond();
        Incoming occasioInsights = new Incoming();
        occasioInsights.setId(2L);
        occasioInsights.setName("Occasio Insights");
        occasioInsights.setNetValue(2642.83f);
        occasioInsights.setPayDay(true);
        occasioInsights.setIncomingMonthDay(30);
        occasioInsights.setAccount(current);
        occasioInsights.setCreatedAt(june2025Sec);
        occasioInsights.setArchived(false);

        // Snapshot recorded when raising/editing to 2642.83 in October 2026
        occasioInsights.setHistoryJson(
                "[{\"timestamp\":" + oct29_26Sec + ",\"netValue\":3111.96,\"grossValue\":3111.96,\"name\":\"Occasio Insights\",\"incomingMonthDay\":30,\"payDay\":true}]"
        );

        HistoricSpentViewModel vm = HistoricSpentCalculator.calculate(
                Collections.emptyList(),
                List.of(current),
                List.of(occasioInsights, oldJob),
                payday,
                today,
                UK,
                500f
        );

        // 1. Current cycle (30 Oct 2026 - 30 Nov 2026): £2,642.83
        HistoricCycleSpent currentCycle = vm.getCurrentCycle();
        assertThat(currentCycle).isNotNull();
        assertThat(currentCycle.getCycleStartDate()).isEqualTo(oct30_26);
        assertThat(currentCycle.getMonthlyIncome()).isEqualTo(2642.83);

        // 2. September 2026 cycle (30 Sep 2026 - 30 Oct 2026): £3,111.96 (effective value before Oct 2026 edit)
        HistoricCycleSpent sepCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(sep30_26))
                .findFirst().orElseThrow();
        assertThat(sepCycle.getMonthlyIncome()).isEqualTo(3111.96);

        // 3. August 2026 cycle (28 Aug 2026 - 30 Sep 2026): £3,111.96
        HistoricCycleSpent augCycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(aug28_26))
                .findFirst().orElseThrow();
        assertThat(augCycle.getMonthlyIncome()).isEqualTo(3111.96);

        // 4. Before June 2025 (30 May 2025 cycle): £4,168.00 (old salary before June 2025)
        HistoricCycleSpent may25Cycle = vm.getCycles().stream()
                .filter(c -> c.getCycleStartDate().equals(may30_25))
                .findFirst().orElseThrow();
        assertThat(may25Cycle.getMonthlyIncome()).isEqualTo(4168.00);
    }
}
