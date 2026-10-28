package viewModels;

import models.Account;
import models.Balance;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class SpogTest {

    @Test
    public void testBaselineDiffCalculations() {
        Account acc = new Account();
        acc.setType(Account.AccountType.DEBIT);
        acc.setName("Current");
        acc.setAvailableLimit(0f);
        acc.outgoings = Collections.emptyList();
        acc.balances = new ArrayList<>();

        List<Account> allAccounts = Collections.singletonList(acc);
        List<Account> monthlyPotAccounts = Collections.singletonList(acc);

        Spog spog = new Spog(
                800f,
                31,
                0,
                2000f,
                1200f,
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                allAccounts,
                monthlyPotAccounts
        );

        // With 0 balance in account, monthlyPotLeftPerDay is 0.
        // It is behind baseline.
        assertThat(spog.isBehindBaseline()).isTrue();
        assertThat(spog.getBaselineDiffTotal()).isLessThan(0.0);
        assertThat(spog.getAbsoluteBaselineDiffTotal()).isGreaterThan(0.0);
    }

    @Test
    public void testSpentThisMonthAndDailyOverspend() {
        Account acc = new Account();
        acc.setId(1L);
        acc.setType(Account.AccountType.DEBIT);
        acc.setName("Current");
        acc.setAvailableLimit(0f);
        acc.outgoings = Collections.emptyList();

        long nowSec = System.currentTimeMillis() / 1000L;
        Balance b0 = new Balance();
        b0.setValue(800f);
        b0.setTimestamp(nowSec - 86400); // 1 day ago
        b0.setAccount(acc);

        Balance b1 = new Balance();
        b1.setValue(300f);
        b1.setTimestamp(nowSec); // now
        b1.setAccount(acc);

        List<Balance> balances = new ArrayList<>();
        balances.add(b0);
        balances.add(b1);
        acc.balances = balances;

        List<Account> allAccounts = Collections.singletonList(acc);
        List<Account> monthlyPotAccounts = Collections.singletonList(acc);

        // Start = 800, now = 300. Negative delta = -500 -> Spent this month = 500
        Spog spog = new Spog(
                800f,
                31,
                0,
                2000f,
                1200f,
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                allAccounts,
                monthlyPotAccounts
        );

        assertThat(spog.getMonthlyPotTotalAvailable()).isEqualTo(300.0);
        assertThat(spog.getSpentThisMonth()).isEqualTo(500.0);

        if (spog.getDaysSinceLastPayday() > 0) {
            assertThat(spog.getActualDailySpend()).isGreaterThan(0.0);
            assertThat(spog.getAbsoluteDailyOverspend()).isNotNull();
        }

        // 1. Halfway through month, adding an account whose balance remained constant should NOT increase spent
        Account acc2 = new Account();
        acc2.setId(2L);
        acc2.setType(Account.AccountType.DEBIT);
        acc2.setName("Constant Account");
        acc2.setAvailableLimit(0f);
        acc2.outgoings = Collections.emptyList();
        Balance bConst = new Balance();
        bConst.setValue(250f);
        bConst.setTimestamp(nowSec - 172800); // 2 days ago
        bConst.setAccount(acc2);
        acc2.balances = Collections.singletonList(bConst);

        List<Account> monthlyWithAddedAccount = List.of(acc, acc2);
        Spog spogWithAdded = new Spog(
                800f, 31, 0, 2000f, 1200f, 0f, 0f, 0f, 0f, 0f, 0f,
                monthlyWithAddedAccount, monthlyWithAddedAccount
        );
        // Balance in acc2 remained constant, so spent is still 500.0
        assertThat(spogWithAdded.getSpentThisMonth()).isEqualTo(500.0);
        // But total available increased by 250 (300 + 250 = 550)
        assertThat(spogWithAdded.getMonthlyPotTotalAvailable()).isEqualTo(550.0);

        // 2. Adding more money to a pot at sometime during the month
        // 800 -> 300 (spent 500) -> 600 (top-up +300) -> 450 (spent 150)
        Balance bTopUp = new Balance();
        bTopUp.setValue(600f);
        bTopUp.setTimestamp(nowSec + 100);
        bTopUp.setAccount(acc);

        Balance bSpendAgain = new Balance();
        bSpendAgain.setValue(450f);
        bSpendAgain.setTimestamp(nowSec + 200);
        bSpendAgain.setAccount(acc);

        acc.balances = List.of(b0, b1, bTopUp, bSpendAgain);
        Spog spogWithTopUp = new Spog(
                800f, 31, 0, 2000f, 1200f, 0f, 0f, 0f, 0f, 0f, 0f,
                Collections.singletonList(acc), Collections.singletonList(acc)
        );
        // Total spent = 500 + 150 = 650.0
        assertThat(spogWithTopUp.getSpentThisMonth()).isEqualTo(650.0);
        assertThat(spogWithTopUp.getMonthlyPotTotalAvailable()).isEqualTo(450.0);
    }

    @Test
    public void testPensionAndLoanAccounts() {
        // Pension account
        Account pension = new Account();
        pension.setType(Account.AccountType.PENSION);
        pension.setName("Aviva Pension");
        pension.setAvailableLimit(0f);
        pension.outgoings = Collections.emptyList();
        Balance pb = new Balance();
        pb.setValue(25000f);
        pb.setTimestamp(System.currentTimeMillis());
        pb.setAccount(pension);
        pension.balances = Collections.singletonList(pb);

        // Short term savings account
        Account shortSavings = new Account();
        shortSavings.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        shortSavings.setName("Easy Access");
        shortSavings.setAvailableLimit(0f);
        shortSavings.outgoings = Collections.emptyList();
        Balance sb = new Balance();
        sb.setValue(1500f);
        sb.setTimestamp(System.currentTimeMillis());
        sb.setAccount(shortSavings);
        shortSavings.balances = Collections.singletonList(sb);

        // Credit account
        Account credit = new Account();
        credit.setType(Account.AccountType.CREDIT);
        credit.setName("Barclaycard");
        credit.setAvailableLimit(2000f);
        credit.outgoings = Collections.emptyList();
        Balance cb = new Balance();
        cb.setValue(1500f); // 1500 limit remaining, 500 spent
        cb.setTimestamp(System.currentTimeMillis());
        cb.setAccount(credit);
        credit.balances = Collections.singletonList(cb);

        // Loan account (Original Drawdown = 10,000, Remaining = 6,500)
        Account loan = new Account();
        loan.setType(Account.AccountType.LOAN);
        loan.setName("Car Loan");
        loan.setAvailableLimit(10000f); // Original drawdown
        loan.outgoings = Collections.emptyList();
        Balance lb = new Balance();
        lb.setValue(6500f); // Remaining balance
        lb.setTimestamp(System.currentTimeMillis());
        lb.setAccount(loan);
        loan.balances = Collections.singletonList(lb);

        List<Account> allAccounts = List.of(pension, shortSavings, credit, loan);
        List<Account> monthlyPotAccounts = Collections.emptyList();

        Spog spog = new Spog(
                500f,
                31,
                0,
                3000f,
                2000f,
                0f,
                0f,
                0f,
                0f,
                0f,
                0f,
                allAccounts,
                monthlyPotAccounts
        );

        // Pension is in total savings pot, but NOT liquid savings pot
        assertThat(spog.getPensionPot()).isEqualTo(25000.0);
        assertThat(spog.getLiquidSavingsPot()).isEqualTo(1500.0);
        assertThat(spog.getSavingsPot()).isEqualTo(26500.0); // 25000 + 1500

        // Loan does not affect credit calculations
        assertThat(spog.getCreditLimit()).isEqualTo(2000.0);
        assertThat(spog.getCreditBalance()).isEqualTo(500.0); // 2000 - 1500
        assertThat(spog.getLoanRemainingTotal()).isEqualTo(6500.0);
        assertThat(spog.getLoanOriginalDrawdownTotal()).isEqualTo(10000.0);

        // Neither Pension nor Loan affect Debit or Credit available spend pools
        assertThat(spog.getTotalAvailableDebit()).isEqualTo(0.0);
        assertThat(spog.getTotalAvailableCredit()).isEqualTo(1500.0);
    }

    @Test
    public void testGroupedAccountStatusesSplitting() {
        Account debit = new Account();
        debit.setType(Account.AccountType.DEBIT);
        debit.setName("Main Current");
        debit.balances = Collections.emptyList();
        debit.outgoings = Collections.emptyList();

        Account sharedBills = new Account();
        sharedBills.setType(Account.AccountType.DEBIT_SHARED_BILLS);
        sharedBills.setName("Joint Bills");
        sharedBills.balances = Collections.emptyList();
        sharedBills.outgoings = Collections.emptyList();

        Account credit = new Account();
        credit.setType(Account.AccountType.CREDIT);
        credit.setName("Amex Card");
        credit.balances = Collections.emptyList();
        credit.outgoings = Collections.emptyList();

        Account shortTerm = new Account();
        shortTerm.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        shortTerm.setName("Emergency Fund");
        shortTerm.balances = Collections.emptyList();
        shortTerm.outgoings = Collections.emptyList();

        Account longTerm = new Account();
        longTerm.setType(Account.AccountType.LONG_TERM_SAVINGS);
        longTerm.setName("Stocks ISA");
        longTerm.balances = Collections.emptyList();
        longTerm.outgoings = Collections.emptyList();

        Account pension = new Account();
        pension.setType(Account.AccountType.PENSION);
        pension.setName("Nest Pension");
        pension.balances = Collections.emptyList();
        pension.outgoings = Collections.emptyList();

        Account loan = new Account();
        loan.setType(Account.AccountType.LOAN);
        loan.setName("Car Loan");
        loan.balances = Collections.emptyList();
        loan.outgoings = Collections.emptyList();

        List<Account> allAccounts = List.of(debit, sharedBills, credit, shortTerm, longTerm, pension, loan);
        Spog spog = new Spog(
                500f, 31, 0, 3000f, 2000f, 0f, 0f, 0f, 0f, 0f, 0f,
                allAccounts, Collections.emptyList()
        );

        java.util.LinkedHashMap<String, List<java.util.Map.Entry<Account, viewModels.AccountStatus>>> grouped =
                spog.getGroupedAccountStatuses();

        assertThat(grouped.keySet()).containsExactly(
                "Debit Accounts",
                "Shared Bills (Debit) Accounts",
                "Credit Accounts",
                "Short-Term Savings Accounts",
                "Long-Term Savings Accounts",
                "Pensions",
                "Loans"
        );
        assertThat(grouped.get("Debit Accounts").get(0).getKey().getName()).isEqualTo("Main Current");
        assertThat(grouped.get("Shared Bills (Debit) Accounts").get(0).getKey().getName()).isEqualTo("Joint Bills");
        assertThat(grouped.get("Short-Term Savings Accounts").get(0).getKey().getName()).isEqualTo("Emergency Fund");
        assertThat(grouped.get("Long-Term Savings Accounts").get(0).getKey().getName()).isEqualTo("Stocks ISA");
    }

    @Test
    public void testIsFreshAccountWhenNoAccounts() {
        Spog emptySpog = new Spog(
                0f, 30, 0, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                Collections.emptyList(), Collections.emptyList()
        );
        assertThat(emptySpog.isFreshAccount()).isTrue();

        Account acc = new Account();
        acc.setName("Current");
        acc.setType(Account.AccountType.DEBIT);
        Spog populatedSpog = new Spog(
                100f, 30, 0, 1000f, 500f, 0f, 0f, 0f, 0f, 0f, 0f,
                List.of(acc), List.of(acc)
        );
        assertThat(populatedSpog.isFreshAccount()).isFalse();
    }

    @Test
    public void testAccountFreshnessIndicatorFreshAndStale() {
        java.time.LocalDate testToday = java.time.LocalDate.of(2025, 5, 10);
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/London");

        // Outgoing on day 5 (already due on 5 May in 1-31 cycle)
        models.Outgoing out1 = new models.Outgoing();
        out1.setName("Gym Membership");
        out1.setCost(30.0f);
        out1.setOutgoingDay(5);

        // Account with balance recorded on 6 May (AFTER the 5 May payment -> FRESH)
        Account accFresh = new Account();
        accFresh.setName("Barclays Fresh");
        accFresh.setType(Account.AccountType.DEBIT);
        accFresh.outgoings = List.of(out1);
        Balance balFresh = new Balance();
        balFresh.setValue(500.0f);
        balFresh.setTimestamp(java.time.LocalDate.of(2025, 5, 6).atStartOfDay(zone).toEpochSecond());
        balFresh.setAccount(accFresh);
        accFresh.balances = List.of(balFresh);

        // Account with balance recorded on 2 May (BEFORE the 5 May payment -> STALE)
        Account accStale = new Account();
        accStale.setName("Monzo Stale");
        accStale.setType(Account.AccountType.DEBIT);
        accStale.outgoings = List.of(out1);
        Balance balStale = new Balance();
        balStale.setValue(400.0f);
        balStale.setTimestamp(java.time.LocalDate.of(2025, 5, 2).atStartOfDay(zone).toEpochSecond());
        balStale.setAccount(accStale);
        accStale.balances = List.of(balStale);

        // Account with no payments due yet this cycle (Outgoing on day 20, testToday is day 10)
        models.Outgoing outFuture = new models.Outgoing();
        outFuture.setName("Internet");
        outFuture.setCost(40.0f);
        outFuture.setOutgoingDay(20);

        Account accNoDue = new Account();
        accNoDue.setName("Santander No Due");
        accNoDue.setType(Account.AccountType.DEBIT);
        accNoDue.outgoings = List.of(outFuture);
        Balance balNoDue = new Balance();
        balNoDue.setValue(600.0f);
        balNoDue.setTimestamp(java.time.LocalDate.of(2025, 5, 3).atStartOfDay(zone).toEpochSecond());
        balNoDue.setAccount(accNoDue);
        accNoDue.balances = List.of(balNoDue);

        // Account with no balance recorded
        Account accNoBal = new Account();
        accNoBal.setName("Virgin No Bal");
        accNoBal.setType(Account.AccountType.DEBIT);
        accNoBal.outgoings = List.of(out1);
        accNoBal.balances = Collections.emptyList();

        List<Account> allAccounts = List.of(accFresh, accStale, accNoDue, accNoBal);

        Spog spog = new Spog(
                500f, 1, 0, 2500f, 1000f, 0f, 0f, 0f, 0f, 0f, 0f,
                allAccounts, allAccounts, testToday, null, null
        );

        java.util.Map<Account, AccountStatus> statusMap = spog.getAccountStatusMap();

        AccountStatus freshStatus = statusMap.get(accFresh);
        assertThat(freshStatus.getFreshnessStatus()).isEqualTo(AccountStatus.FreshnessStatus.FRESH);
        assertThat(freshStatus.isFresh()).isTrue();
        assertThat(freshStatus.isStale()).isFalse();
        assertThat(freshStatus.getFreshnessMessage()).contains("Up to date");

        AccountStatus staleStatus = statusMap.get(accStale);
        assertThat(staleStatus.getFreshnessStatus()).isEqualTo(AccountStatus.FreshnessStatus.STALE);
        assertThat(staleStatus.isStale()).isTrue();
        assertThat(staleStatus.isFresh()).isFalse();
        assertThat(staleStatus.getFreshnessMessage()).contains("Update needed");

        AccountStatus noDueStatus = statusMap.get(accNoDue);
        assertThat(noDueStatus.getFreshnessStatus()).isEqualTo(AccountStatus.FreshnessStatus.NO_PAYMENT_DUE);
        assertThat(noDueStatus.isFresh()).isFalse();
        assertThat(noDueStatus.isStale()).isFalse();

        AccountStatus noBalStatus = statusMap.get(accNoBal);
        assertThat(noBalStatus.getFreshnessStatus()).isEqualTo(AccountStatus.FreshnessStatus.NO_BALANCE);
        assertThat(noBalStatus.isFresh()).isFalse();
        assertThat(noBalStatus.isStale()).isFalse();
    }

    @Test
    public void testLastMonthSpentField() {
        Spog spog = new Spog(
                500f, 1, 0, 2000f, 1000f, 0f, 0f, 0f, 0f, 0f, 0f,
                Collections.emptyList(), Collections.emptyList()
        );
        assertThat(spog.getLastMonthSpent()).isNull();

        spog.setLastMonthSpent(432.50);
        assertThat(spog.getLastMonthSpent()).isEqualTo(432.50);
    }
}
