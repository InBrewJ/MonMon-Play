package viewModels;

import models.Account;
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

        // Max per day available will be surplus / daysBetweenPaydays.
        // If surplus is 800 and daysBetweenPaydays is around 28-31, maxPerDay is ~25-28.
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
}
