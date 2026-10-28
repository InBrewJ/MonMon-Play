package services;

import helpers.HistoricSpentCalculator;
import models.Account;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.Pot;
import org.junit.Test;
import viewModels.HistoricCycleSpent;
import viewModels.HistoricSpentViewModel;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class BankSyncAndStatementTest {

    private static class InMemoryBankTransactionRepository implements BankTransactionRepository {
        final List<BankTransaction> storage = new ArrayList<>();

        @Override
        public CompletionStage<BankTransaction> save(BankTransaction transaction) {
            storage.add(transaction);
            return CompletableFuture.completedFuture(transaction);
        }

        @Override
        public CompletionStage<Integer> saveAll(List<BankTransaction> transactions) {
            storage.addAll(transactions);
            return CompletableFuture.completedFuture(transactions.size());
        }

        @Override
        public CompletionStage<Stream<BankTransaction>> listRecent(String userId, int limit) {
            return CompletableFuture.completedFuture(storage.stream()
                    .filter(t -> userId.equals(t.getUserId()))
                    .limit(limit));
        }

        @Override
        public CompletionStage<Stream<BankTransaction>> findByAccount(Long accountId, Long startSec, Long endSec) {
            return CompletableFuture.completedFuture(storage.stream()
                    .filter(t -> accountId.equals(t.getAccountId())
                            && t.getTimestamp() >= startSec && t.getTimestamp() <= endSec));
        }

        @Override
        public CompletionStage<Double> getAccountSpentSum(Long accountId, Long startSec, Long endSec) {
            double sum = storage.stream()
                    .filter(t -> accountId.equals(t.getAccountId())
                            && t.getTimestamp() >= startSec && t.getTimestamp() <= endSec && t.getAmount() > 0)
                    .mapToDouble(BankTransaction::getAmount)
                    .sum();
            return CompletableFuture.completedFuture(sum);
        }

        @Override
        public CompletionStage<Map<Long, Double>> getAccountsSpentSumMap(String userId, Long startSec, Long endSec) {
            Map<Long, Double> map = new HashMap<>();
            for (BankTransaction t : storage) {
                if (userId.equals(t.getUserId()) && t.getTimestamp() >= startSec && t.getTimestamp() <= endSec && t.getAmount() > 0) {
                    map.merge(t.getAccountId(), (double) t.getAmount(), Double::sum);
                }
            }
            return CompletableFuture.completedFuture(map);
        }

        @Override
        public CompletionStage<Integer> deleteForAccount(Long accountId) {
            int before = storage.size();
            storage.removeIf(t -> accountId.equals(t.getAccountId()));
            return CompletableFuture.completedFuture(before - storage.size());
        }

        @Override
        public CompletionStage<Integer> deleteByTimeRange(Long accountId, Long startSec, Long endSec) {
            int before = storage.size();
            storage.removeIf(t -> accountId.equals(t.getAccountId())
                    && t.getTimestamp() != null && t.getTimestamp() >= startSec && t.getTimestamp() <= endSec);
            return CompletableFuture.completedFuture(before - storage.size());
        }
    }

    @Test
    public void testStatementParserParsesCsvAndHandlesOutflowsAndInflows() {
        InMemoryBankTransactionRepository repo = new InMemoryBankTransactionRepository();
        StatementParserService parser = new StatementParserService(repo);

        String csv = "Date,Description,Amount,Category\n" +
                "2026-10-20,Tesco Superstore,-42.50,Groceries\n" +
                "2026-10-21,Salary,2500.00,Income\n" +
                "2026-10-22,Shell Petrol,-60.00,Fuel\n";

        List<BankTransaction> txList = parser.parseCsv("user1", 101L, csv);
        assertThat(txList).hasSize(3);

        BankTransaction tesco = txList.get(0);
        assertThat(tesco.getDescription()).isEqualTo("Tesco Superstore");
        assertThat(tesco.getAmount()).isEqualTo(42.50f); // outflow positive spend
        assertThat(tesco.getCategory()).isEqualTo("Groceries");

        BankTransaction fuel = txList.get(2);
        assertThat(fuel.getDescription()).isEqualTo("Shell Petrol");
        assertThat(fuel.getAmount()).isEqualTo(60.00f);
    }

    @Test
    public void testDangerouslyRewriteHistoryPurgesDateRangeAndReplaces() {
        InMemoryBankTransactionRepository repo = new InMemoryBankTransactionRepository();
        StatementParserService parser = new StatementParserService(repo);

        // Prepopulate an existing transaction on 2026-10-20
        BankTransaction oldTx = new BankTransaction("user1", 101L, "old-1", 1792454400L, "2026-10-20", 15.0f, "Old Coffee", null, "General", "MANUAL", false);
        repo.save(oldTx);
        assertThat(repo.storage).hasSize(1);

        String newCsv = "Date,Description,Amount\n" +
                "2026-10-20,Corrected Grocery Expense,-85.00\n";

        BankSyncService.SyncResult result = parser.importStatement("user1", 101L, newCsv, true).toCompletableFuture().join();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("Dangerously rewrote history");

        // The old transaction should have been purged and replaced by the single new transaction
        assertThat(repo.storage).hasSize(1);
        assertThat(repo.storage.get(0).getDescription()).isEqualTo("Corrected Grocery Expense");
        assertThat(repo.storage.get(0).getAmount()).isEqualTo(85.00f);
    }

    @Test
    public void testMockBankSyncHandles32AccountsCleanlyAndRapidly() {
        InMemoryBankTransactionRepository repo = new InMemoryBankTransactionRepository();
        StatementParserService parser = new StatementParserService(repo);
        MockBankSyncProvider mockProvider = new MockBankSyncProvider(repo, parser);

        // Create 32 accounts simulating user's live environment
        List<Account> accounts = new ArrayList<>();
        for (long i = 1; i <= 32; i++) {
            Account acc = new Account();
            acc.setId(i);
            acc.setUserId("user32");
            if (i <= 20) {
                acc.setName("Current / Debit " + i);
                acc.setType(Account.AccountType.DEBIT);
            } else if (i <= 28) {
                acc.setName("Credit Card " + i);
                acc.setType(Account.AccountType.CREDIT);
            } else if (i <= 30) {
                acc.setName("Savings " + i);
                acc.setType(Account.AccountType.SHORT_TERM_SAVINGS);
            } else {
                acc.setName("Pension " + i);
                acc.setType(Account.AccountType.PENSION);
            }
            accounts.add(acc);
        }

        long start = System.currentTimeMillis();
        BankSyncService.SyncResult result = mockProvider.syncAllUserAccounts("user32", accounts, 14).toCompletableFuture().join();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getImportedCount()).isGreaterThan(200);
        assertThat(elapsed).isLessThan(2000L); // High speed across 32 accounts

        // Pensions (accounts 31 and 32) should have been skipped from open banking sync
        assertThat(repo.storage.stream().anyMatch(t -> t.getAccountId().equals(31L) || t.getAccountId().equals(32L))).isFalse();
    }

    @Test
    public void testHistoricSpentCalculatorHonorsTransactionBasedLedgerMode() {
        LocalDate today = LocalDate.of(2026, 10, 28);
        ZoneId zone = ZoneId.of("Europe/London");

        Account acc = new Account();
        acc.setId(99L);
        acc.setName("Current Account");
        acc.setType(Account.AccountType.DEBIT);
        acc.setAvailableLimit(0f);
        acc.outgoings = Collections.emptyList();
        acc.balances = new ArrayList<>();

        models.Balance b1 = new models.Balance();
        b1.setId(1L);
        b1.setValue(1000f);
        b1.setTimestamp(today.minusDays(20).atStartOfDay(zone).toEpochSecond());
        b1.setAccount(acc);
        acc.balances.add(b1);

        models.Balance b2 = new models.Balance();
        b2.setId(2L);
        b2.setValue(700f);
        b2.setTimestamp(today.atStartOfDay(zone).toEpochSecond());
        b2.setAccount(acc);
        acc.balances.add(b2);

        Pot monthlyPot = new Pot();
        monthlyPot.id = 1L;
        monthlyPot.type = Pot.PotType.MONTHLY;
        monthlyPot.createdAt = today.minusDays(25).atStartOfDay(zone).toEpochSecond();
        monthlyPot.accounts = Collections.singletonList(acc);

        // 1. In ABL mode (Available-based ledger):
        // Spend is startBal (1000) - endBal (700) = 300
        HistoricSpentViewModel ablVm = HistoricSpentCalculator.calculate(
                Collections.singletonList(monthlyPot),
                Collections.singletonList(acc),
                Collections.emptyList(),
                1,
                today,
                zone,
                500f,
                Collections.emptyMap() // No TBL transactions
        );
        HistoricCycleSpent currentCycleAbl = ablVm.getCycles().get(0);
        assertThat(currentCycleAbl.getTotalSpent()).isEqualTo(300.0);

        // 2. In TBL mode (Accurate mode with exact line-item transactions):
        // Suppose transaction sum is 342.50 (e.g., specific micro-transactions / pending holds)
        Map<Long, Double> tblSpent = Collections.singletonMap(99L, 342.50);
        HistoricSpentViewModel tblVm = HistoricSpentCalculator.calculate(
                Collections.singletonList(monthlyPot),
                Collections.singletonList(acc),
                Collections.emptyList(),
                1,
                today,
                zone,
                500f,
                tblSpent
        );
        HistoricCycleSpent currentCycleTbl = tblVm.getCycles().get(0);
        assertThat(currentCycleTbl.getTotalSpent()).isEqualTo(342.50);
    }
}
