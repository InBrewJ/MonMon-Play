package viewModels;

import models.Account;
import models.Balance;
import models.NetWorthAsset;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class NetWorthTest {

    private static Account createAccount(long id, String name, Account.AccountType type, float limit, float latestBalance) {
        Account a = new Account();
        a.setId(id);
        a.setName(name);
        a.setType(type);
        a.setAvailableLimit(limit);
        a.balances = new ArrayList<>();
        Balance b = new Balance();
        b.setId(id * 100);
        b.setValue(latestBalance);
        b.setTimestamp(System.currentTimeMillis());
        b.setAccount(a);
        a.balances.add(b);
        return a;
    }

    @Test
    public void testNetWorthCombinesCashPensionsCustomAssetsAndDeductsDebts() {
        // Cash: Current (£1,500) + Savings (£8,500) = £10,000
        Account current = createAccount(1L, "Main Current", Account.AccountType.DEBIT, 0f, 1500f);
        Account savings = createAccount(2L, "Easy Access", Account.AccountType.SHORT_TERM_SAVINGS, 0f, 8500f);

        // Pension: £60,000
        Account pension = createAccount(3L, "Workplace SIPP", Account.AccountType.PENSION, 0f, 60000f);

        // Credit Card: Limit £3,000, balance £2,200 available -> £800 owed
        Account credit = createAccount(4L, "Barclaycard", Account.AccountType.CREDIT, 3000f, 2200f);

        // Loan: £5,000 remaining
        Account loan = createAccount(5L, "Car Loan", Account.AccountType.LOAN, 10000f, 5000f);

        // Custom Assets: Flat (£250,000) + Car (£12,000) = £262,000
        NetWorthAsset flat = new NetWorthAsset("user1", "2-bed Flat", NetWorthAsset.AssetCategory.PROPERTY, 250000.0, "Mortgaged");
        NetWorthAsset car = new NetWorthAsset("user1", "VW Golf", NetWorthAsset.AssetCategory.VEHICLE, 12000.0, "Clean");

        List<Account> accounts = List.of(current, savings, pension, credit, loan);
        List<NetWorthAsset> customAssets = List.of(flat, car);

        NetWorthViewModel vm = new NetWorthViewModel(accounts, customAssets, 500.0, 200.0);

        // Assets verification
        assertThat(vm.getCashTotal()).isEqualTo(10000.0);
        assertThat(vm.getPensionTotal()).isEqualTo(60000.0);
        assertThat(vm.getCustomAssetsTotal()).isEqualTo(262000.0);
        assertThat(vm.getTotalAssets()).isEqualTo(332000.0); // 10k + 60k + 262k

        // Liabilities verification
        assertThat(vm.getCreditDebtTotal()).isEqualTo(800.0);
        assertThat(vm.getLoanDebtTotal()).isEqualTo(5000.0);
        assertThat(vm.getTotalLiabilities()).isEqualTo(5800.0);

        // Net Worth = 332,000 - 5,800 = 326,200
        assertThat(vm.getNetWorth()).isEqualTo(326200.0);

        // Investable portfolio = Cash (10k) + Pension (60k) = 70,000
        assertThat(vm.getInvestablePortfolio()).isEqualTo(70000.0);

        // 4% rule safe withdrawal: 70,000 * 0.04 = £2,800/yr -> £233.33/mo
        assertThat(vm.getAnnualSafeWithdrawal()).isEqualTo(2800.0);
        assertThat(vm.getMonthlySafeWithdrawal()).isEqualTo(233.33);

        // Food coverage: monthly food cost £200, safe withdrawal £233.33 -> 117% covered
        assertThat(vm.getFoodCoveredPercent()).isEqualTo(117);
    }

    @Test
    public void testArchivedAccountsAndAssetsAreExcluded() {
        Account active = createAccount(1L, "Current", Account.AccountType.DEBIT, 0f, 2000f);
        Account archivedAcc = createAccount(2L, "Old Account", Account.AccountType.DEBIT, 0f, 5000f);
        archivedAcc.setArchived(true);

        NetWorthAsset activeAsset = new NetWorthAsset("user1", "Watch", NetWorthAsset.AssetCategory.VALUABLE, 3000.0, null);
        NetWorthAsset archivedAsset = new NetWorthAsset("user1", "Old Car", NetWorthAsset.AssetCategory.VEHICLE, 10000.0, null);
        archivedAsset.setArchived(true);

        NetWorthViewModel vm = new NetWorthViewModel(List.of(active, archivedAcc), List.of(activeAsset, archivedAsset), 0.0, 200.0);

        assertThat(vm.getCashTotal()).isEqualTo(2000.0);
        assertThat(vm.getCustomAssetsTotal()).isEqualTo(3000.0);
        assertThat(vm.getNetWorth()).isEqualTo(5000.0);
    }
}
