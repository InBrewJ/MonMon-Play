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
import java.util.stream.Collectors;
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

        // 3. User switches account explicitly to ABL mode:
        // Even though transactions still exist in tblSpent, the engine uses ABL (300.0)
        // and all existing balance data remains completely intact and untouched!
        acc.setLedgerMode(Account.LedgerMode.ABL);
        HistoricSpentViewModel revertedAblVm = HistoricSpentCalculator.calculate(
                Collections.singletonList(monthlyPot),
                Collections.singletonList(acc),
                Collections.emptyList(),
                1,
                today,
                zone,
                500f,
                tblSpent
        );
        assertThat(revertedAblVm.getCycles().get(0).getTotalSpent()).isEqualTo(300.0);
        assertThat(acc.getBalances()).hasSize(2); // Balance data 100% preserved!
    }

    private static class InMemoryMonzoConnectionRepository implements models.MonzoConnectionRepository {
        models.MonzoConnection connection;

        @Override
        public CompletionStage<models.MonzoConnection> save(models.MonzoConnection conn) {
            this.connection = conn;
            return CompletableFuture.completedFuture(conn);
        }

        @Override
        public CompletionStage<Optional<models.MonzoConnection>> findByUserId(String userId) {
            if (connection != null && userId.equals(connection.getUserId())) {
                return CompletableFuture.completedFuture(Optional.of(connection));
            }
            return CompletableFuture.completedFuture(Optional.empty());
        }

        @Override
        public CompletionStage<Optional<models.MonzoConnection>> findFirst() {
            return CompletableFuture.completedFuture(Optional.ofNullable(connection));
        }

        @Override
        public CompletionStage<Boolean> deleteByUserId(String userId) {
            boolean existed = connection != null;
            this.connection = null;
            return CompletableFuture.completedFuture(existed);
        }
    }

    @Test
    public void testMonzoAccountInfoRecognizesDebitAndMonzoFlexCreditCards() {
        MonzoBankSyncProvider.MonzoAccountInfo debit =
                new MonzoBankSyncProvider.MonzoAccountInfo("acc_123", "uk_retail", "Personal Current Account", false);
        MonzoBankSyncProvider.MonzoAccountInfo flex =
                new MonzoBankSyncProvider.MonzoAccountInfo("acc_flex_456", "uk_monzo_flex", "Monzo Flex Credit Card", false);
        MonzoBankSyncProvider.MonzoAccountInfo joint =
                new MonzoBankSyncProvider.MonzoAccountInfo("acc_joint_789", "uk_retail_joint", "Joint Account", false);

        assertThat(debit.isDebit()).isTrue();
        assertThat(debit.isFlexCreditCard()).isFalse();
        assertThat(debit.getDisplayName()).contains("Current Account (Debit)");

        assertThat(flex.isFlexCreditCard()).isTrue();
        assertThat(flex.isDebit()).isFalse();
        assertThat(flex.getDisplayName()).contains("Monzo Flex (Credit Card)");

        assertThat(joint.isDebit()).isTrue();
        assertThat(joint.getDisplayName()).contains("Joint Account (Debit)");
    }

    @Test
    public void testMonzoSyncSkipsIncompatiblePensionAndLoanAccounts() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser);

        Account pension = new Account();
        pension.setId(301L);
        pension.setName("Workplace Pension");
        pension.setType(Account.AccountType.PENSION);
        BankSyncService.SyncResult penRes = provider.syncAccount(pension, 30).toCompletableFuture().join();
        assertThat(penRes.isSuccess()).isTrue();
        assertThat(penRes.getMessage()).contains("Pension/Loan requires manual ABL entry");

        Account loan = new Account();
        loan.setId(302L);
        loan.setName("Car Loan");
        loan.setType(Account.AccountType.LOAN);
        BankSyncService.SyncResult loanRes = provider.syncAccount(loan, 30).toCompletableFuture().join();
        assertThat(loanRes.isSuccess()).isTrue();
        assertThat(loanRes.getMessage()).contains("Pension/Loan requires manual ABL entry");
    }

    @Test
    public void testMonzoConnectionRepositorySaveFindAndDelete() {
        InMemoryMonzoConnectionRepository repo = new InMemoryMonzoConnectionRepository();
        models.MonzoConnection conn = new models.MonzoConnection("user_abc", "token_123", "refresh_123", "monzo_u_123", 999999999L);

        models.MonzoConnection saved = repo.save(conn).toCompletableFuture().join();
        assertThat(saved.getAccessToken()).isEqualTo("token_123");

        Optional<models.MonzoConnection> found = repo.findByUserId("user_abc").toCompletableFuture().join();
        assertThat(found).isPresent();
        assertThat(found.get().getMonzoUserId()).isEqualTo("monzo_u_123");

        Boolean deleted = repo.deleteByUserId("user_abc").toCompletableFuture().join();
        assertThat(deleted).isTrue();
        assertThat(repo.findByUserId("user_abc").toCompletableFuture().join()).isEmpty();
    }

    @Test
    public void testMonzoSyncProviderWithBalanceRepositoryForABLMode() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser, balanceRepo);
        assertThat(provider.getProviderName()).isEqualTo("MONZO");

        // Verify balance repository works
        models.Balance b = new models.Balance();
        b.setUserId("user_xyz");
        b.setValue(1250.75f);
        b.setTimestamp(1700000000L);
        balanceRepo.add(b).toCompletableFuture().join();

        List<models.Balance> savedBalances = balanceRepo.list("user_xyz").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(savedBalances).hasSize(1);
        assertThat(savedBalances.get(0).getValue()).isEqualTo(1250.75f);
    }

    @Test
    public void testConvertToAvailableBalanceForCreditAndDebitAccounts() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser);

        Account flexAccount = new Account();
        flexAccount.setType(Account.AccountType.CREDIT);
        flexAccount.setAvailableLimit(3000.0f); // £3000 credit limit in MonMon

        // When Monzo reports £900 spent / drawn on Flex:
        float flexAvailable1 = provider.convertToAvailableBalance(flexAccount, 900.0f);
        assertThat(flexAvailable1).isEqualTo(2100.0f);

        // When Monzo reports -£900:
        float flexAvailable2 = provider.convertToAvailableBalance(flexAccount, -900.0f);
        assertThat(flexAvailable2).isEqualTo(2100.0f);

        // For debit accounts, balance is unchanged:
        Account debitAccount = new Account();
        debitAccount.setType(Account.AccountType.DEBIT);
        debitAccount.setAvailableLimit(500.0f);
        float debitAvailable = provider.convertToAvailableBalance(debitAccount, 450.50f);
        assertThat(debitAvailable).isEqualTo(450.50f);
    }

    @Test
    public void testHandleWebhookEventParsesLocationAndSavesTransaction() throws Exception {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        models.MonzoConnection conn = new models.MonzoConnection("user1", "access_tok", "refresh_tok", "monzo_user1", 999999999L);
        connRepo.save(conn).toCompletableFuture().join();

        Account debitAcc = new Account();
        debitAcc.setId(101L);
        debitAcc.setName("Monzo Current");
        debitAcc.setType(Account.AccountType.DEBIT);
        debitAcc.setUserId("user1");

        models.AccountRepository accRepo = org.mockito.Mockito.mock(models.AccountRepository.class);
        org.mockito.Mockito.when(accRepo.list("user1"))
                .thenReturn(CompletableFuture.completedFuture(Stream.of(debitAcc)));

        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser, balanceRepo, accRepo);

        String webhookJson = "{\n" +
                "  \"type\": \"transaction.created\",\n" +
                "  \"data\": {\n" +
                "    \"id\": \"tx_test_12345\",\n" +
                "    \"account_id\": \"acc_0000B8tDR6WlkkoFEOsxcJ\",\n" +
                "    \"amount\": -525,\n" +
                "    \"created\": \"2026-10-28T14:30:00Z\",\n" +
                "    \"currency\": \"GBP\",\n" +
                "    \"description\": \"GREGGS ABBEY WOOD\",\n" +
                "    \"merchant\": {\n" +
                "      \"name\": \"Greggs\",\n" +
                "      \"emoji\": \"🥖\",\n" +
                "      \"category\": \"eating_out\",\n" +
                "      \"address\": {\n" +
                "        \"formatted\": \"7 Wilton Road, London SE2 9RH, United Kingdom\",\n" +
                "        \"latitude\": 51.490015,\n" +
                "        \"longitude\": 0.1212292\n" +
                "      }\n" +
                "    }\n" +
                "  }\n" +
                "}";

        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode payload = mapper.readTree(webhookJson);

        Boolean result = provider.handleWebhookEvent("user1", payload).toCompletableFuture().join();
        assertThat(result).isTrue();

        assertThat(txRepo.storage).hasSize(1);
        BankTransaction tx = txRepo.storage.get(0);
        assertThat(tx.getExternalId()).isEqualTo("tx_test_12345");
        assertThat(tx.getAmount()).isEqualTo(5.25f);
        assertThat(tx.getDescription()).isEqualTo("Greggs");
        assertThat(tx.getMerchantName()).isEqualTo("Greggs");
        assertThat(tx.getCategory()).isEqualTo("eating_out");
        assertThat(tx.getMerchantAddress()).isEqualTo("7 Wilton Road, London SE2 9RH, United Kingdom");
        assertThat(tx.getLatitude()).isEqualTo(51.490015);
        assertThat(tx.getLongitude()).isEqualTo(0.1212292);
        assertThat(tx.getMerchantEmoji()).isEqualTo("🥖");

        // Verify connection lastReceivedAt updated
        Optional<models.MonzoConnection> updatedConn = connRepo.findByUserId("user1").toCompletableFuture().join();
        assertThat(updatedConn).isPresent();
        assertThat(updatedConn.get().getLastReceivedAt()).isNotNull();
    }

    static class InMemoryBalanceRepository implements models.BalanceRepository {
        final List<models.Balance> balances = new ArrayList<>();

        @Override
        public CompletionStage<models.Balance> add(models.Balance balance) {
            balances.add(balance);
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public CompletionStage<Stream<models.Balance>> list(String userId) {
            return CompletableFuture.completedFuture(balances.stream().filter(b -> userId.equals(b.getUserId())));
        }

        @Override
        public CompletionStage<Stream<models.Balance>> listSince(String userId, Long sinceTimestamp) {
            return CompletableFuture.completedFuture(balances.stream().filter(b -> userId.equals(b.getUserId()) && b.getTimestamp() >= sinceTimestamp));
        }
    }
}
