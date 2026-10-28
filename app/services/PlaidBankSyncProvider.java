package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import models.Account;
import models.BankTransaction;
import models.BankTransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

@Singleton
public class PlaidBankSyncProvider implements BankSyncService {

    private static final Logger logger = LoggerFactory.getLogger(PlaidBankSyncProvider.class);

    private final Config config;
    private final BankTransactionRepository bankTransactionRepository;
    private final StatementParserService statementParserService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    public PlaidBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 StatementParserService statementParserService) {
        this(config, bankTransactionRepository, statementParserService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public PlaidBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 StatementParserService statementParserService,
                                 HttpClient httpClient) {
        this.config = config;
        this.bankTransactionRepository = bankTransactionRepository;
        this.statementParserService = statementParserService;
        this.httpClient = httpClient;
    }

    @Override
    public String getProviderName() {
        return "PLAID";
    }

    private String getClientId() {
        if (config.hasPath("plaid.clientId")) {
            return config.getString("plaid.clientId");
        }
        String env = System.getenv("PLAID_CLIENT_ID");
        return env != null ? env : "";
    }

    private String getSecret() {
        if (config.hasPath("plaid.secret")) {
            return config.getString("plaid.secret");
        }
        String env = System.getenv("PLAID_SECRET");
        return env != null ? env : "";
    }

    private String getEnv() {
        if (config.hasPath("plaid.env")) {
            return config.getString("plaid.env");
        }
        String env = System.getenv("PLAID_ENV");
        return (env != null && !env.isEmpty()) ? env : "sandbox";
    }

    private String getBaseUrl() {
        String env = getEnv().toLowerCase();
        if ("development".equals(env)) {
            return "https://development.plaid.com";
        } else if ("production".equals(env)) {
            return "https://production.plaid.com";
        }
        return "https://sandbox.plaid.com";
    }

    public boolean isConfigured() {
        return !getClientId().isEmpty() && !getSecret().isEmpty();
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
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(SyncResult.error(
                    "Plaid is not configured. Set PLAID_CLIENT_ID and PLAID_SECRET or switch to MOCK provider / CSV Statement Upload."));
        }

        // In Plaid, accounts are linked via Plaid Item access tokens.
        // We call Plaid /transactions/sync
        return syncPlaidTransactions(account.getUserId(), account.getId(), "access-sandbox-monmon", null);
    }

    @Override
    public CompletionStage<SyncResult> syncAllUserAccounts(String userId, List<Account> accounts, int daysBack) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(SyncResult.error(
                    "Plaid is not configured. Set PLAID_CLIENT_ID and PLAID_SECRET in environment or conf/application.conf."));
        }
        if (accounts == null || accounts.isEmpty()) {
            return CompletableFuture.completedFuture(SyncResult.ok(0, "No accounts to sync."));
        }

        List<CompletableFuture<SyncResult>> futures = new ArrayList<>();
        for (Account a : accounts) {
            futures.add(syncAccount(a, daysBack).toCompletableFuture());
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    int total = 0;
                    List<String> details = new ArrayList<>();
                    for (CompletableFuture<SyncResult> f : futures) {
                        try {
                            SyncResult res = f.join();
                            if (res.isSuccess()) {
                                total += res.getImportedCount();
                                details.add(res.getMessage());
                            } else {
                                details.add("Error: " + res.getMessage());
                            }
                        } catch (Exception ex) {
                            details.add("Error: " + ex.getMessage());
                        }
                    }
                    return SyncResult.ok(total, "Plaid sync completed across " + accounts.size() + " accounts (" + total + " transactions).", details);
                });
    }

    @Override
    public CompletionStage<SyncResult> importStatement(String userId, Long accountId, String statementData, boolean rewriteHistory) {
        return statementParserService.importStatement(userId, accountId, statementData, rewriteHistory);
    }

    private CompletionStage<SyncResult> syncPlaidTransactions(String userId, Long accountId, String accessToken, String cursor) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("client_id", getClientId());
            body.put("secret", getSecret());
            body.put("access_token", accessToken);
            if (cursor != null && !cursor.isEmpty()) {
                body.put("cursor", cursor);
            }
            body.put("count", 500);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(getBaseUrl() + "/transactions/sync"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenCompose(response -> {
                        if (response.statusCode() != 200) {
                            logger.warn("Plaid /transactions/sync returned status {}: {}", response.statusCode(), response.body());
                            return CompletableFuture.completedFuture(
                                    SyncResult.error("Plaid API error (" + response.statusCode() + "): " + response.body()));
                        }
                        try {
                            JsonNode json = mapper.readTree(response.body());
                            JsonNode added = json.get("added");
                            List<BankTransaction> txList = new ArrayList<>();
                            if (added != null && added.isArray()) {
                                for (JsonNode node : added) {
                                    String extId = node.path("transaction_id").asText();
                                    float amount = (float) node.path("amount").asDouble(0.0);
                                    String date = node.path("date").asText();
                                    String name = node.path("name").asText("Transaction");
                                    String merchant = node.path("merchant_name").asText(name);
                                    String category = "General";
                                    if (node.has("category") && node.get("category").isArray() && node.get("category").size() > 0) {
                                        category = node.get("category").get(0).asText();
                                    }
                                    boolean pending = node.path("pending").asBoolean(false);

                                    long epochSec = System.currentTimeMillis() / 1000L;
                                    try {
                                        epochSec = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                                    } catch (Exception ignored) {
                                    }

                                    txList.add(new BankTransaction(
                                            userId,
                                            accountId,
                                            extId,
                                            epochSec,
                                            date,
                                            amount,
                                            name,
                                            merchant,
                                            category,
                                            "PLAID",
                                            pending
                                    ));
                                }
                            }
                            return bankTransactionRepository.saveAll(txList)
                                    .thenApply(count -> SyncResult.ok(count, "Successfully synced " + count + " transactions from Plaid"));
                        } catch (Exception e) {
                            return CompletableFuture.completedFuture(SyncResult.error("Failed to parse Plaid response: " + e.getMessage()));
                        }
                    });
        } catch (Exception e) {
            return CompletableFuture.completedFuture(SyncResult.error("Plaid request failed: " + e.getMessage()));
        }
    }
}
