package viewModels;

import helpers.HistoricSpentCalculator;
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
}
