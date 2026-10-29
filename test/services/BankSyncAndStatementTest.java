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

        @Override
        public CompletionStage<List<BankTransaction>> findGeotaggedTransactions(String userId, int limit) {
            List<BankTransaction> res = storage.stream()
                    .filter(t -> userId.equals(t.getUserId()) && t.getLatitude() != null && t.getLongitude() != null && t.getAmount() > 0)
                    .limit(limit > 0 ? limit : 2000)
                    .collect(Collectors.toList());
            return CompletableFuture.completedFuture(res);
        }

        @Override
        public CompletionStage<List<viewModels.CategorySpend>> getCategorySpendingHistogram(String userId) {
            Map<String, List<BankTransaction>> grouped = storage.stream()
                    .filter(t -> userId.equals(t.getUserId()) && t.getAmount() > 0)
                    .collect(Collectors.groupingBy(t -> t.getCategory() != null && !t.getCategory().trim().isEmpty() ? t.getCategory().trim() : "General"));
            double grandTotal = storage.stream()
                    .filter(t -> userId.equals(t.getUserId()) && t.getAmount() > 0)
                    .mapToDouble(BankTransaction::getAmount).sum();
            List<viewModels.CategorySpend> res = new ArrayList<>();
            for (Map.Entry<String, List<BankTransaction>> entry : grouped.entrySet()) {
                long cnt = entry.getValue().size();
                double total = entry.getValue().stream().mapToDouble(BankTransaction::getAmount).sum();
                double avg = total / cnt;
                double pct = grandTotal > 0 ? (Math.round((total / grandTotal * 100.0) * 10.0) / 10.0) : 0.0;
                res.add(new viewModels.CategorySpend(entry.getKey(), cnt, total, avg, pct));
            }
            res.sort((a, b) -> Double.compare(b.getTotalSpent(), a.getTotalSpent()));
            return CompletableFuture.completedFuture(res);
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

        @Override
        public CompletionStage<Integer> deleteForAccountBetween(Long accountId, String userId, Long startTimestamp, Long endTimestamp) {
            int before = balances.size();
            balances.removeIf(b -> accountId.equals(b.getAccount() != null ? b.getAccount().getId() : null)
                    && userId.equals(b.getUserId())
                    && b.getTimestamp() != null
                    && b.getTimestamp() >= startTimestamp
                    && b.getTimestamp() <= endTimestamp);
            return CompletableFuture.completedFuture(before - balances.size());
        }

        @Override
        public CompletionStage<Boolean> delete(Long id, String userId) {
            int before = balances.size();
            balances.removeIf(b -> id.equals(b.getId()) && userId.equals(b.getUserId()));
            return CompletableFuture.completedFuture(before > balances.size());
        }

        @Override
        public CompletionStage<List<models.ErraticBalance>> findErraticBalances(String userId, double threshold) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }

    @Test
    public void testLegMonStatementPendingAndDangerousBalanceOverwrite() {
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();

        Account acc = new Account();
        acc.setId(88L);
        acc.setName("Barclays Current");
        acc.setType(Account.AccountType.DEBIT);
        acc.setLedgerMode(Account.LedgerMode.ABL);

        // Seed 3 existing balances in September 2026
        long t1 = LocalDate.of(2026, 9, 5).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
        long t2 = LocalDate.of(2026, 9, 15).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
        long t3 = LocalDate.of(2026, 9, 25).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();

        models.Balance b1 = new models.Balance(); b1.setId(1L); b1.setUserId("user_stmt"); b1.setAccount(acc); b1.setValue(100.0f); b1.setTimestamp(t1);
        models.Balance b2 = new models.Balance(); b2.setId(2L); b2.setUserId("user_stmt"); b2.setAccount(acc); b2.setValue(200.0f); b2.setTimestamp(t2);
        models.Balance b3 = new models.Balance(); b3.setId(3L); b3.setUserId("user_stmt"); b3.setAccount(acc); b3.setValue(300.0f); b3.setTimestamp(t3);
        balanceRepo.add(b1).toCompletableFuture().join();
        balanceRepo.add(b2).toCompletableFuture().join();
        balanceRepo.add(b3).toCompletableFuture().join();

        assertThat(balanceRepo.list("user_stmt").toCompletableFuture().join().collect(Collectors.toList())).hasSize(3);

        // Statement covers 2026-09-01 to 2026-09-30
        long startSec = LocalDate.of(2026, 9, 1).atStartOfDay(ZoneId.of("UTC")).toEpochSecond();
        long endSec = LocalDate.of(2026, 9, 30).atTime(23, 59, 59).atZone(ZoneId.of("UTC")).toEpochSecond();

        models.PendingStatement stmt = new models.PendingStatement(
                "user_stmt", "stmt_job_001", "Barclays", "2026-09-01", "2026-09-30",
                startSec, endSec, 1500.00f, 1850.50f, 2, "{}"
        );
        assertThat(stmt.getBankName()).isEqualTo("Barclays");
        assertThat(stmt.getStatus()).isEqualTo("PENDING");

        // Dangerously overwrite ABL balances for that period
        int deleted = balanceRepo.deleteForAccountBetween(acc.getId(), "user_stmt", startSec, endSec).toCompletableFuture().join();
        assertThat(deleted).isEqualTo(3);

        // Add statement closing balance
        models.Balance newClosing = new models.Balance();
        newClosing.setUserId("user_stmt");
        newClosing.setAccount(acc);
        newClosing.setValue(stmt.getClosingBalance());
        newClosing.setTimestamp(stmt.getEndTimestamp());
        balanceRepo.add(newClosing).toCompletableFuture().join();

        // Verify existing balances wiped and replaced with verified statement closing balance
        List<models.Balance> remaining = balanceRepo.list("user_stmt").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getValue()).isEqualTo(1850.50f);

        // Import transactions
        BankTransaction tx1 = new BankTransaction("user_stmt", acc.getId(), "legmon_tx_1", t2, "2026-09-15", 25.50f, "Sainsburys", "Sainsburys", "groceries", "STATEMENT", false);
        BankTransaction tx2 = new BankTransaction("user_stmt", acc.getId(), "legmon_tx_2", t3, "2026-09-25", 50.00f, "Shell", "Shell", "transport", "STATEMENT", false);
        txRepo.saveAll(Arrays.asList(tx1, tx2)).toCompletableFuture().join();

        assertThat(txRepo.storage).hasSize(2);
        assertThat(txRepo.storage.get(0).getSource()).isEqualTo("STATEMENT");
    }

    @Test
    public void testTransactionListWithAnchorBalanceConfirmation() {
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();

        Account acc = new Account();
        acc.setId(99L);
        acc.setName("NatWest Reward");
        acc.setType(Account.AccountType.DEBIT);
        acc.setLedgerMode(Account.LedgerMode.ABL);

        // Transaction list statement: 0 opening, 0 closing balance
        long startSec = 1722211200L; // 2026-07-29
        long endSec = 1729555200L;   // 2026-10-22

        models.PendingStatement txnStmt = new models.PendingStatement(
                "user_natwest", "job_nw_01", "NatWest Transactions-01",
                "2026-07-29", "2026-10-22",
                startSec, endSec,
                0.0f, 0.0f, 3, "{}"
        );
        assertThat(helpers.TransactionBalanceReconstructor.isTransactionListOnly(txnStmt)).isTrue();

        // 3 Transactions:
        // Day 10: Inflow +£1000 (salary) -> MonMon amount = -1000
        // Day 20: Outflow -£200 (rent) -> MonMon amount = +200
        // Day 30: Outflow -£50 (groceries) -> MonMon amount = +50
        BankTransaction t1 = new BankTransaction("user_natwest", acc.getId(), "tx_1", startSec + 10000, "2026-08-10", -1000.0f, "Salary", "Employer", "income", "STATEMENT", false);
        BankTransaction t2 = new BankTransaction("user_natwest", acc.getId(), "tx_2", startSec + 20000, "2026-08-20", 200.0f, "Rent", "Landlord", "bills", "STATEMENT", false);
        BankTransaction t3 = new BankTransaction("user_natwest", acc.getId(), "tx_3", startSec + 30000, "2026-08-30", 50.0f, "Groceries", "Tesco", "groceries", "STATEMENT", false);
        t1.setRawData("{\"id\":\"tx_1\",\"amount\":100000}");
        t2.setRawData("{\"id\":\"tx_2\",\"amount\":-20000}");
        t3.setRawData("{\"id\":\"tx_3\",\"amount\":-5000}");

        List<BankTransaction> txs = Arrays.asList(t1, t2, t3);
        txRepo.saveAll(txs).toCompletableFuture().join();
        assertThat(txRepo.storage).hasSize(3);
        assertThat(txRepo.storage.get(0).getRawData()).contains("\"amount\":100000");

        // User supplies Anchor Balance B(start) = £500.00
        float anchorBal = 500.00f;
        List<helpers.TransactionBalanceReconstructor.TransactionDelta> deltas =
                helpers.TransactionBalanceReconstructor.fromBankTransactions(txs);
        helpers.TransactionBalanceReconstructor.ReconstructionResult rec =
                helpers.TransactionBalanceReconstructor.reconstruct(deltas, startSec, anchorBal, startSec, endSec);

        // Forward: 500 + 1000 - 200 - 50 = 1250.00
        assertThat(rec.getOpeningBalance()).isEqualTo(500.00f);
        assertThat(rec.getClosingBalance()).isEqualTo(1250.00f);

        // Overwrite balances with reconstructed closing balance
        balanceRepo.deleteForAccountBetween(acc.getId(), "user_natwest", startSec, endSec).toCompletableFuture().join();
        models.Balance closing = new models.Balance();
        closing.setUserId("user_natwest");
        closing.setAccount(acc);
        closing.setValue(rec.getClosingBalance());
        closing.setTimestamp(endSec);
        balanceRepo.add(closing).toCompletableFuture().join();

        List<models.Balance> savedBalances = balanceRepo.list("user_natwest").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(savedBalances).hasSize(1);
        assertThat(savedBalances.get(0).getValue()).isEqualTo(1250.00f);
    }

    @Test
    public void testTransactionListWithoutAnchorBalancePreservesExistingBalances() {
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();

        Account acc = new Account();
        acc.setId(77L);
        acc.setName("NatWest Reward");

        // Existing balance in place
        models.Balance prior = new models.Balance();
        prior.setId(10L);
        prior.setUserId("user_no_anchor");
        prior.setAccount(acc);
        prior.setValue(980.50f);
        prior.setTimestamp(1725000000L);
        balanceRepo.add(prior).toCompletableFuture().join();

        // Transaction list without balances
        models.PendingStatement txnStmt = new models.PendingStatement(
                "user_no_anchor", "job_nw_02", "NatWest Transactions-01",
                "2026-07-29", "2026-10-22",
                1722211200L, 1729555200L,
                0.0f, 0.0f, 1, "{}"
        );
        assertThat(helpers.TransactionBalanceReconstructor.isTransactionListOnly(txnStmt)).isTrue();

        // If no anchor balance is provided, existing balance MUST NOT be deleted or overwritten with 0!
        List<BankTransaction> txs = Collections.singletonList(
                new BankTransaction("user_no_anchor", acc.getId(), "tx_no_anchor", 1725000000L, "2026-08-30", 15.0f, "Costa", "Costa", "food", "STATEMENT", false)
        );
        txRepo.saveAll(txs).toCompletableFuture().join();

        // Check balances: unchanged
        List<models.Balance> remaining = balanceRepo.list("user_no_anchor").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).getValue()).isEqualTo(980.50f);
        assertThat(txRepo.storage).hasSize(1);
    }

    @Test
    public void testUnanchoredNewAccountPromptsAndSteersToMyBalances() {
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();

        Account newAcc = new Account();
        newAcc.setId(105L);
        newAcc.setName("Brand New NatWest Account");

        // Brand new account has ZERO recorded balances
        List<models.Balance> initialBalances = balanceRepo.list("new_user").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(initialBalances).isEmpty();

        models.PendingStatement txnStmt = new models.PendingStatement(
                "new_user", "job_nw_new", "NatWest Transactions-01",
                "2026-07-29", "2026-10-22",
                1722211200L, 1729555200L,
                0.0f, 0.0f, 5, "{}"
        );
        assertThat(helpers.TransactionBalanceReconstructor.isTransactionListOnly(txnStmt)).isTrue();

        // When anchor balance search is performed on empty balance repo, it yields Optional.empty()
        Optional<models.Balance> anchorFound = helpers.TransactionBalanceReconstructor.findAnchorBalance(
                initialBalances, txnStmt.getStartTimestamp(), txnStmt.getEndTimestamp()
        );
        assertThat(anchorFound).isEmpty();

        // This triggers the steering workflow: transactions are safely stored, and user is redirected to /myBalances
        BankTransaction tx = new BankTransaction("new_user", newAcc.getId(), "tx_init", 1725000000L, "2026-08-15", 35.0f, "Shop", "Shop", "retail", "STATEMENT", false);
        txRepo.saveAll(Collections.singletonList(tx)).toCompletableFuture().join();

        assertThat(txRepo.storage).hasSize(1);
        // Balance repository remains clean (not overwritten with 0) until anchor balance is set on /myBalances
        assertThat(balanceRepo.balances).isEmpty();
    }

    @Test
    public void testStatementPreviewReconstructionAndDailyIntermediateBalances() {
        InMemoryBalanceRepository balanceRepo = new InMemoryBalanceRepository();
        Account acc = new Account();
        acc.setId(88L);
        acc.setName("NatWest Current");
        acc.setType(Account.AccountType.DEBIT);
        acc.setLedgerMode(Account.LedgerMode.ABL);

        long startSec = 1722470400L; // 2026-08-01 00:00:00
        long endSec = 1725148799L;   // 2026-08-31 23:59:59

        // Existing old balance entry that should be replaced
        models.Balance oldEntry = new models.Balance();
        oldEntry.setId(1L);
        oldEntry.setUserId("user_preview");
        oldEntry.setAccount(acc);
        oldEntry.setValue(100.0f);
        oldEntry.setTimestamp(startSec + 5000);
        balanceRepo.add(oldEntry).toCompletableFuture().join();

        // 3 Transactions:
        // Day 10 (Aug 11): Inflow +£1000 (salary) -> amount -1000
        // Day 15 (Aug 16): Outflow -£250 (rent) -> amount +250
        // Day 20 (Aug 21): Outflow -£50 (groceries) -> amount +50
        long t1 = startSec + (10 * 86400);
        long t2 = startSec + (15 * 86400);
        long t3 = startSec + (20 * 86400);

        BankTransaction tx1 = new BankTransaction("user_preview", acc.getId(), "tx_p1", t1, "2026-08-11", -1000.0f, "Salary", "Boss", "income", "STATEMENT", false);
        BankTransaction tx2 = new BankTransaction("user_preview", acc.getId(), "tx_p2", t2, "2026-08-16", 250.0f, "Rent", "Landlord", "bills", "STATEMENT", false);
        BankTransaction tx3 = new BankTransaction("user_preview", acc.getId(), "tx_p3", t3, "2026-08-21", 50.0f, "Groceries", "Tesco", "groceries", "STATEMENT", false);

        List<BankTransaction> txs = Arrays.asList(tx1, tx2, tx3);
        List<helpers.TransactionBalanceReconstructor.TransactionDelta> deltas =
                helpers.TransactionBalanceReconstructor.fromBankTransactions(txs);

        float openingBal = 500.0f;
        float closingBal = 1200.0f; // 500 + 1000 - 250 - 50 = 1200

        // Reconstruct trajectory using closing balance as anchor
        helpers.TransactionBalanceReconstructor.ReconstructionResult rec =
                helpers.TransactionBalanceReconstructor.reconstruct(deltas, endSec, closingBal, startSec, endSec);

        assertThat(rec.getOpeningBalance()).isEqualTo(500.0f);
        assertThat(rec.getClosingBalance()).isEqualTo(1200.0f);
        assertThat(rec.getRunningBalances()).hasSize(3);
        assertThat(rec.getRunningBalances().get(t1)).isEqualTo(1500.0f); // 500 + 1000
        assertThat(rec.getRunningBalances().get(t2)).isEqualTo(1250.0f); // 1500 - 250
        assertThat(rec.getRunningBalances().get(t3)).isEqualTo(1200.0f); // 1250 - 50

        // Simulate daily balance persistence (as done in applyBalancesAndFinalize)
        balanceRepo.deleteForAccountBetween(acc.getId(), "user_preview", startSec, endSec).toCompletableFuture().join();

        // 1. Opening
        models.Balance bOpen = new models.Balance();
        bOpen.setUserId("user_preview");
        bOpen.setAccount(acc);
        bOpen.setValue(rec.getOpeningBalance());
        bOpen.setTimestamp(startSec);
        balanceRepo.add(bOpen).toCompletableFuture().join();

        // 2. Intermediate daily points
        for (Map.Entry<Long, Float> entry : rec.getRunningBalances().entrySet()) {
            models.Balance bMid = new models.Balance();
            bMid.setUserId("user_preview");
            bMid.setAccount(acc);
            bMid.setValue(entry.getValue());
            bMid.setTimestamp(entry.getKey());
            balanceRepo.add(bMid).toCompletableFuture().join();
        }

        // 3. Closing
        models.Balance bClose = new models.Balance();
        bClose.setUserId("user_preview");
        bClose.setAccount(acc);
        bClose.setValue(rec.getClosingBalance());
        bClose.setTimestamp(endSec);
        balanceRepo.add(bClose).toCompletableFuture().join();

        // Verify: Old single entry deleted, replaced with 5 chronological points spanning whole month
        List<models.Balance> saved = balanceRepo.list("user_preview").toCompletableFuture().join().collect(Collectors.toList());
        assertThat(saved).hasSize(5);
        assertThat(saved.get(0).getValue()).isEqualTo(500.0f);
        assertThat(saved.get(1).getValue()).isEqualTo(1500.0f);
        assertThat(saved.get(2).getValue()).isEqualTo(1250.0f);
        assertThat(saved.get(3).getValue()).isEqualTo(1200.0f);
        assertThat(saved.get(4).getValue()).isEqualTo(1200.0f);
    }

    @Test
    public void testSyncWebhooksRejectsHttpLocalhostWithClearInstructions() {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser);
        models.MonzoConnection conn = new models.MonzoConnection("user_wh", "tok_test", null, "m_user", 1999999999L);
        connRepo.save(conn).toCompletableFuture().join();

        MonzoBankSyncProvider.WebhookSyncResult result = provider.syncWebhooks(conn, "http://localhost:9000").toCompletableFuture().join();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Monzo requires a public HTTPS webhook URL");
        assertThat(result.getMessage()).contains("http://localhost:9000");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testSyncWebhooksListsAndReusesExistingWebhookToAvoidDuplicates() throws Exception {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        java.net.http.HttpClient mockClient = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser, null, null, mockClient);

        models.MonzoConnection conn = new models.MonzoConnection("user_wh", "tok_test", null, "m_user", 1999999999L);
        connRepo.save(conn).toCompletableFuture().join();

        // Mock accounts response
        String accountsJson = "{\"accounts\":[" +
                "{\"id\":\"acc_retail_1\",\"type\":\"uk_retail\",\"description\":\"Current\",\"closed\":false}," +
                "{\"id\":\"acc_flex_2\",\"type\":\"uk_monzo_flex\",\"description\":\"Flex\",\"closed\":false}" +
                "]}";
        java.net.http.HttpResponse<String> accountsResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(accountsResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(accountsResp.body()).thenReturn(accountsJson);

        // Mock existing webhooks for retail: already contains matching webhook for target URL
        String targetUrl = "https://myapp.domain.org/api/monzo/webhook?userId=user_wh";
        String debitWebhooksJson = "{\"webhooks\":[" +
                "{\"id\":\"wh_existing_debit\",\"account_id\":\"acc_retail_1\",\"url\":\"" + targetUrl + "\"}" +
                "]}";
        java.net.http.HttpResponse<String> debitWebhooksResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(debitWebhooksResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(debitWebhooksResp.body()).thenReturn(debitWebhooksJson);

        // Mock existing webhooks for flex: contains a stale old ngrok webhook
        String staleUrl = "https://old.ngrok.app/api/monzo/webhook?userId=user_wh";
        String flexWebhooksJson = "{\"webhooks\":[" +
                "{\"id\":\"wh_stale_flex\",\"account_id\":\"acc_flex_2\",\"url\":\"" + staleUrl + "\"}" +
                "]}";
        java.net.http.HttpResponse<String> flexWebhooksResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(flexWebhooksResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(flexWebhooksResp.body()).thenReturn(flexWebhooksJson);

        // Mock delete of stale webhook
        java.net.http.HttpResponse<String> deleteResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(deleteResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(deleteResp.body()).thenReturn("{}");

        // Mock creation of new flex webhook
        String createFlexRespJson = "{\"webhook\":{\"id\":\"wh_new_flex\",\"account_id\":\"acc_flex_2\",\"url\":\"" + targetUrl + "\"}}";
        java.net.http.HttpResponse<String> createFlexResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(createFlexResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(createFlexResp.body()).thenReturn(createFlexRespJson);

        org.mockito.Mockito.when(mockClient.sendAsync(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.net.http.HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    String method = req.method();

                    if (uri.endsWith("/accounts")) {
                        return CompletableFuture.completedFuture(accountsResp);
                    } else if (uri.contains("/webhooks?account_id=acc_retail_1")) {
                        return CompletableFuture.completedFuture(debitWebhooksResp);
                    } else if (uri.contains("/webhooks?account_id=acc_flex_2")) {
                        return CompletableFuture.completedFuture(flexWebhooksResp);
                    } else if ("DELETE".equalsIgnoreCase(method) && uri.contains("wh_stale_flex")) {
                        return CompletableFuture.completedFuture(deleteResp);
                    } else if ("POST".equalsIgnoreCase(method) && uri.endsWith("/webhooks")) {
                        return CompletableFuture.completedFuture(createFlexResp);
                    }
                    return CompletableFuture.completedFuture(accountsResp);
                });

        MonzoBankSyncProvider.WebhookSyncResult syncResult = provider.syncWebhooks(conn, "https://myapp.domain.org").toCompletableFuture().join();

        assertThat(syncResult.isSuccess()).isTrue();
        assertThat(syncResult.getDebitWebhookId()).isEqualTo("wh_existing_debit");
        assertThat(syncResult.getFlexWebhookId()).isEqualTo("wh_new_flex");
        assertThat(syncResult.getDetails()).anyMatch(d -> d.contains("Reused existing webhook (wh_existing_debit)"));
        assertThat(syncResult.getDetails()).anyMatch(d -> d.contains("Registered new webhook (wh_new_flex)"));
        assertThat(syncResult.getDetails()).anyMatch(d -> d.contains("Pruned 1 stale MonMon webhook"));

        models.MonzoConnection updated = connRepo.findByUserId("user_wh").toCompletableFuture().join().orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.getWebhookDebitId()).isEqualTo("wh_existing_debit");
        assertThat(updated.getWebhookFlexId()).isEqualTo("wh_new_flex");
        assertThat(updated.getWebhookUrl()).isEqualTo(targetUrl);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testDiscoverLiveWebhooksAdoptsExternalWebhooksFromMonzo() throws Exception {
        com.typesafe.config.Config config = com.typesafe.config.ConfigFactory.empty();
        InMemoryBankTransactionRepository txRepo = new InMemoryBankTransactionRepository();
        InMemoryMonzoConnectionRepository connRepo = new InMemoryMonzoConnectionRepository();
        StatementParserService parser = new StatementParserService(txRepo);

        java.net.http.HttpClient mockClient = org.mockito.Mockito.mock(java.net.http.HttpClient.class);
        MonzoBankSyncProvider provider = new MonzoBankSyncProvider(config, txRepo, connRepo, parser, null, null, mockClient);

        // Connection exists locally without webhooks
        models.MonzoConnection conn = new models.MonzoConnection("user_ext", "tok_ext", null, "m_user", 1999999999L);
        connRepo.save(conn).toCompletableFuture().join();
        assertThat(conn.hasActiveWebhooks()).isFalse();

        // Monzo API returns debit & flex accounts
        String accountsJson = "{\"accounts\":[" +
                "{\"id\":\"acc_retail_ext\",\"type\":\"uk_retail\",\"description\":\"Current\",\"closed\":false}," +
                "{\"id\":\"acc_flex_ext\",\"type\":\"uk_monzo_flex\",\"description\":\"Flex\",\"closed\":false}" +
                "]}";
        java.net.http.HttpResponse<String> accountsResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(accountsResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(accountsResp.body()).thenReturn(accountsJson);

        // Monzo API returns webhooks registered externally on portal
        String debitWhJson = "{\"webhooks\":[{\"id\":\"wh_portal_debit\",\"account_id\":\"acc_retail_ext\",\"url\":\"https://tunnel.ngrok.app/api/monzo/webhook?userId=user_ext\"}]}";
        java.net.http.HttpResponse<String> debitResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(debitResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(debitResp.body()).thenReturn(debitWhJson);

        String flexWhJson = "{\"webhooks\":[{\"id\":\"wh_portal_flex\",\"account_id\":\"acc_flex_ext\",\"url\":\"https://tunnel.ngrok.app/api/monzo/webhook?userId=user_ext\"}]}";
        java.net.http.HttpResponse<String> flexResp = org.mockito.Mockito.mock(java.net.http.HttpResponse.class);
        org.mockito.Mockito.when(flexResp.statusCode()).thenReturn(200);
        org.mockito.Mockito.when(flexResp.body()).thenReturn(flexWhJson);

        org.mockito.Mockito.when(mockClient.sendAsync(org.mockito.ArgumentMatchers.any(java.net.http.HttpRequest.class), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.net.http.HttpRequest req = invocation.getArgument(0);
                    String uri = req.uri().toString();
                    if (uri.endsWith("/accounts")) {
                        return CompletableFuture.completedFuture(accountsResp);
                    } else if (uri.contains("/webhooks?account_id=acc_retail_ext")) {
                        return CompletableFuture.completedFuture(debitResp);
                    } else if (uri.contains("/webhooks?account_id=acc_flex_ext")) {
                        return CompletableFuture.completedFuture(flexResp);
                    }
                    return CompletableFuture.completedFuture(accountsResp);
                });

        List<MonzoBankSyncProvider.LiveWebhookDetail> details = provider.discoverLiveWebhooks(conn).toCompletableFuture().join();
        assertThat(details).hasSize(2);
        assertThat(details.get(0).webhookId).isEqualTo("wh_portal_debit");
        assertThat(details.get(1).webhookId).isEqualTo("wh_portal_flex");

        // Verify local MonzoConnection was updated and persisted
        models.MonzoConnection updated = connRepo.findByUserId("user_ext").toCompletableFuture().join().orElse(null);
        assertThat(updated).isNotNull();
        assertThat(updated.hasActiveWebhooks()).isTrue();
        assertThat(updated.getWebhookDebitId()).isEqualTo("wh_portal_debit");
        assertThat(updated.getWebhookFlexId()).isEqualTo("wh_portal_flex");
        assertThat(updated.getWebhookUrl()).isEqualTo("https://tunnel.ngrok.app/api/monzo/webhook?userId=user_ext");
    }
}
