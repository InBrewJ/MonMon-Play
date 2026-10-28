package services;

import models.Account;
import models.BankTransaction;
import models.BankTransactionRepository;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Singleton
public class MockBankSyncProvider implements BankSyncService {

    private final BankTransactionRepository bankTransactionRepository;
    private final StatementParserService statementParserService;

    private static final String[] MERCHANTS = {
            "Tesco Superstore", "Sainsbury's", "Shell Petrol", "BP Fuel", "TFL Travel",
            "Pret A Manger", "Costa Coffee", "Amazon UK", "Netflix", "Spotify",
            "Gym Membership", "Boots Pharmacy", "Deliveroo", "Uber", "Waitrose"
    };

    private static final String[] CATEGORIES = {
            "Groceries", "Fuel", "Transport", "Fun", "Shopping", "Entertainment", "Dining", "Health"
    };

    @Inject
    public MockBankSyncProvider(BankTransactionRepository bankTransactionRepository,
                                StatementParserService statementParserService) {
        this.bankTransactionRepository = bankTransactionRepository;
        this.statementParserService = statementParserService;
    }

    @Override
    public String getProviderName() {
        return "MOCK";
    }

    @Override
    public CompletionStage<SyncResult> syncAccount(Account account, int daysBack) {
        if (account == null) {
            return CompletableFuture.completedFuture(SyncResult.error("Account was null"));
        }
        if (account.getType() == Account.AccountType.PENSION || account.getType() == Account.AccountType.LOAN) {
            return CompletableFuture.completedFuture(
                    SyncResult.ok(0, "Skipped account " + account.getName() + " (Pension/Loan requires manual ABL entry)"));
        }

        List<BankTransaction> txList = generateMockTransactions(
                account.getUserId() != null ? account.getUserId() : "user",
                account.getId() != null ? account.getId() : 1L,
                daysBack > 0 ? daysBack : 30
        );

        return bankTransactionRepository.saveAll(txList)
                .thenApply(count -> SyncResult.ok(count, "Successfully synced " + count + " mock transactions for " + account.getName()));
    }

    @Override
    public CompletionStage<SyncResult> syncAllUserAccounts(String userId, List<Account> accounts, int daysBack) {
        if (accounts == null || accounts.isEmpty()) {
            return CompletableFuture.completedFuture(SyncResult.ok(0, "No accounts to sync."));
        }

        List<Account> eligible = accounts.stream()
                .filter(a -> a != null && a.getType() != Account.AccountType.PENSION && a.getType() != Account.AccountType.LOAN)
                .collect(Collectors.toList());

        List<BankTransaction> allTx = new ArrayList<>();
        int effectiveDays = daysBack > 0 ? daysBack : 30;

        for (Account acc : eligible) {
            Long accId = acc.getId() != null ? acc.getId() : (long) (Math.random() * 10000);
            allTx.addAll(generateMockTransactions(userId, accId, effectiveDays));
        }

        long startTime = System.currentTimeMillis();
        return bankTransactionRepository.saveAll(allTx)
                .thenApply(count -> {
                    long durationMs = System.currentTimeMillis() - startTime;
                    List<String> details = Arrays.asList(
                            "Accounts processed: " + accounts.size() + " total (" + eligible.size() + " transactional, " + (accounts.size() - eligible.size()) + " manual ABL)",
                            "Total transactions generated: " + count,
                            "Time taken: " + durationMs + "ms"
                    );
                    return SyncResult.ok(
                            count,
                            "Mock sync completed for " + eligible.size() + " accounts (" + count + " transactions inserted/updated in " + durationMs + "ms).",
                            details
                    );
                });
    }

    @Override
    public CompletionStage<SyncResult> importStatement(String userId, Long accountId, String statementData, boolean rewriteHistory) {
        return statementParserService.importStatement(userId, accountId, statementData, rewriteHistory);
    }

    public List<BankTransaction> generateMockTransactions(String userId, Long accountId, int daysBack) {
        List<BankTransaction> list = new ArrayList<>();
        LocalDate today = LocalDate.now();
        Random rng = new Random((userId + accountId).hashCode());

        // 1 to 4 transactions per day on average
        for (int d = 0; d < daysBack; d++) {
            LocalDate date = today.minusDays(d);
            long epochSec = date.atStartOfDay(ZoneOffset.UTC).toEpochSecond() + (rng.nextInt(12) + 8) * 3600L;
            int numTx = rng.nextInt(3) + 1; // 1, 2, or 3 txs

            for (int t = 0; t < numTx; t++) {
                int merchantIdx = rng.nextInt(MERCHANTS.length);
                String merchant = MERCHANTS[merchantIdx];
                String category = CATEGORIES[merchantIdx % CATEGORIES.length];
                float amount = (float) (Math.round((5.0 + rng.nextDouble() * 45.0) * 100.0) / 100.0);
                String extId = "mock-" + accountId + "-" + date.toString() + "-" + t;

                list.add(new BankTransaction(
                        userId,
                        accountId,
                        extId,
                        epochSec + (t * 1800L),
                        date.toString(),
                        amount,
                        merchant,
                        merchant,
                        category,
                        "MOCK",
                        false
                ));
            }
        }
        return list;
    }
}
