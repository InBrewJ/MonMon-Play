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
        acc.setType(Account.AccountType.DEBIT);
        acc.setName("Current");
        acc.setAvailableLimit(0f);
        acc.outgoings = Collections.emptyList();

        Balance b = new Balance();
        b.setValue(300f);
        b.setTimestamp(System.currentTimeMillis());
        b.setAccount(acc);

        List<Balance> balances = new ArrayList<>();
        balances.add(b);
        acc.balances = balances;

        List<Account> allAccounts = Collections.singletonList(acc);
        List<Account> monthlyPotAccounts = Collections.singletonList(acc);

        // Surplus = 800 (discretionary budget)
        // Current monthly pot balance = 300
        // Spent this month = 800 - 300 = 500
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
    }
}
