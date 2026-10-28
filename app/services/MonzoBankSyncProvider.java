package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import models.Account;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.MonzoConnection;
import models.MonzoConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

@Singleton
public class MonzoBankSyncProvider implements BankSyncService {

    private static final Logger logger = LoggerFactory.getLogger(MonzoBankSyncProvider.class);
    private static final String MONZO_API_BASE = "https://api.monzo.com";

    private final Config config;
    private final BankTransactionRepository bankTransactionRepository;
    private final MonzoConnectionRepository monzoConnectionRepository;
    private final StatementParserService statementParserService;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public static class MonzoAccountInfo {
        public final String id;
        public final String type; // uk_retail (debit), uk_monzo_flex (credit card), uk_retail_joint
        public final String description;
        public final boolean closed;

        public MonzoAccountInfo(String id, String type, String description, boolean closed) {
            this.id = id;
            this.type = type;
            this.description = description;
            this.closed = closed;
        }

        public boolean isDebit() {
            return "uk_retail".equalsIgnoreCase(type) || "uk_retail_joint".equalsIgnoreCase(type);
        }

        public boolean isFlexCreditCard() {
            return "uk_monzo_flex".equalsIgnoreCase(type);
        }

        public String getDisplayName() {
            if (isFlexCreditCard()) {
                return "Monzo Flex (Credit Card)";
            } else if ("uk_retail_joint".equalsIgnoreCase(type)) {
                return "Monzo Joint Account (Debit)";
            }
            return "Monzo Current Account (Debit)";
        }
    }

    @Inject
    public MonzoBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 MonzoConnectionRepository monzoConnectionRepository,
                                 StatementParserService statementParserService) {
        this(config, bankTransactionRepository, monzoConnectionRepository, statementParserService,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MonzoBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 MonzoConnectionRepository monzoConnectionRepository,
                                 StatementParserService statementParserService,
                                 HttpClient httpClient) {
        this.config = config;
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.statementParserService = statementParserService;
        this.httpClient = httpClient;
    }

    @Override
    public String getProviderName() {
        return "MONZO";
    }

    public String getClientId() {
        if (config.hasPath("monzo.clientId")) {
            return config.getString("monzo.clientId");
        }
        String env = System.getenv("MONZO_CLIENT_ID");
        return env != null ? env : "";
    }

    public String getClientSecret() {
        if (config.hasPath("monzo.clientSecret")) {
            return config.getString("monzo.clientSecret");
        }
        String env = System.getenv("MONZO_CLIENT_SECRET");
        return env != null ? env : "";
    }

    public String getRedirectUri() {
        if (config.hasPath("monzo.redirectUri")) {
            return config.getString("monzo.redirectUri");
        }
        String env = System.getenv("MONZO_REDIRECT_URI");
        return env != null ? env : "https://boom.dryark.uk/bank/monzo/callback";
    }

    public CompletionStage<List<MonzoAccountInfo>> fetchAccounts(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MONZO_API_BASE + "/accounts"))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Monzo API error (" + response.statusCode() + "): " + response.body());
                    }
                    try {
                        JsonNode json = mapper.readTree(response.body());
                        JsonNode accountsNode = json.get("accounts");
                        List<MonzoAccountInfo> accounts = new ArrayList<>();
                        if (accountsNode != null && accountsNode.isArray()) {
                            for (JsonNode acc : accountsNode) {
                                String id = acc.path("id").asText();
                                String type = acc.path("type").asText();
                                String desc = acc.path("description").asText();
                                boolean closed = acc.path("closed").asBoolean(false);
                                if (!closed) {
                                    accounts.add(new MonzoAccountInfo(id, type, desc, closed));
                                }
                            }
                        }
                        return accounts;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse Monzo accounts: " + e.getMessage());
                    }
                });
    }

    public CompletionStage<List<BankTransaction>> fetchTransactions(String userId, Long accountId, String accessToken, String monzoAccountId, int daysBack) {
        int effectiveDays = daysBack > 0 ? daysBack : 30;
        Instant sinceInstant = Instant.now().minus(Duration.ofDays(effectiveDays));
        String sinceIso = DateTimeFormatter.ISO_INSTANT.format(sinceInstant);

        String url = MONZO_API_BASE + "/transactions?account_id=" + monzoAccountId + "&expand[]=merchant&since=" + sinceIso;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Monzo transactions error (" + response.statusCode() + "): " + response.body());
                    }
                    try {
                        JsonNode json = mapper.readTree(response.body());
                        JsonNode txArray = json.get("transactions");
                        List<BankTransaction> list = new ArrayList<>();
                        if (txArray != null && txArray.isArray()) {
                            for (JsonNode tx : txArray) {
                                String extId = tx.path("id").asText();
                                long amountPence = tx.path("amount").asLong(0L);
                                // In Monzo: negative amounts are debits/outflows (spend), positive are deposits
                                // In MonMon: spend is positive float
                                float amountPounds = Math.abs(amountPence) / 100.0f;
                                if (amountPence > 0) {
                                    amountPounds = -amountPounds; // refund / salary / deposit
                                }

                                String createdStr = tx.path("created").asText();
                                long epochSec = System.currentTimeMillis() / 1000L;
                                String dateStr = LocalDate.now().toString();
                                try {
                                    Instant inst = Instant.parse(createdStr);
                                    epochSec = inst.getEpochSecond();
                                    dateStr = inst.atZone(ZoneOffset.UTC).toLocalDate().toString();
                                } catch (Exception ignored) {
                                }

                                String description = tx.path("description").asText("Transaction");
                                String merchantName = null;
                                String category = tx.path("category").asText("General");

                                JsonNode merchantNode = tx.get("merchant");
                                if (merchantNode != null && !merchantNode.isNull()) {
                                    merchantName = merchantNode.path("name").asText(null);
                                    if (merchantName != null && !merchantName.trim().isEmpty()) {
                                        description = merchantName;
                                    }
                                    if (merchantNode.has("category") && !merchantNode.path("category").asText().isEmpty()) {
                                        category = merchantNode.path("category").asText();
                                    }
                                }

                                boolean declined = tx.path("decline_reason").isTextual();
                                if (declined) {
                                    continue; // Skip declined transactions
                                }

                                list.add(new BankTransaction(
                                        userId,
                                        accountId,
                                        extId,
                                        epochSec,
                                        dateStr,
                                        amountPounds,
                                        description,
                                        merchantName,
                                        category,
                                        "MONZO",
                                        false
                                ));
                            }
                        }
                        return list;
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse Monzo transactions: " + e.getMessage());
                    }
                });
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

        return monzoConnectionRepository.findByUserId(account.getUserId()).thenCompose(connOpt -> {
            if (connOpt.isEmpty()) {
                return CompletableFuture.completedFuture(SyncResult.error("No Monzo connection found. Enter your Personal Access Token or connect via Monzo OAuth."));
            }
            MonzoConnection conn = connOpt.get();
            return fetchAccounts(conn.getAccessToken()).thenCompose(monzoAccounts -> {
                if (monzoAccounts.isEmpty()) {
                    return CompletableFuture.completedFuture(SyncResult.error("No active accounts found on your Monzo profile."));
                }
                // Match Monzo account: if account is CREDIT, try uk_monzo_flex; otherwise uk_retail
                MonzoAccountInfo targetCandidate = null;
                if (account.getType() == Account.AccountType.CREDIT) {
                    targetCandidate = monzoAccounts.stream().filter(MonzoAccountInfo::isFlexCreditCard).findFirst().orElse(null);
                }
                if (targetCandidate == null) {
                    targetCandidate = monzoAccounts.stream().filter(MonzoAccountInfo::isDebit).findFirst().orElse(monzoAccounts.get(0));
                }
                final MonzoAccountInfo target = targetCandidate;

                return fetchTransactions(account.getUserId(), account.getId(), conn.getAccessToken(), target.id, daysBack)
                        .thenCompose(bankTransactionRepository::saveAll)
                        .thenApply(count -> SyncResult.ok(count, "Successfully synced " + count + " transactions from " + target.getDisplayName()));
            });
        }).exceptionally(err -> SyncResult.error("Monzo sync error: " + err.getMessage()));
    }

    @Override
    public CompletionStage<SyncResult> syncAllUserAccounts(String userId, List<Account> accounts, int daysBack) {
        return monzoConnectionRepository.findByUserId(userId).thenCompose(connOpt -> {
            if (connOpt.isEmpty()) {
                return CompletableFuture.completedFuture(SyncResult.error(
                        "No Monzo connection active. Paste your Monzo Personal Access Token or connect with Monzo on the dashboard."));
            }
            MonzoConnection conn = connOpt.get();

            return fetchAccounts(conn.getAccessToken()).thenCompose(monzoAccounts -> {
                if (monzoAccounts.isEmpty()) {
                    return CompletableFuture.completedFuture(SyncResult.ok(0, "No Monzo accounts found."));
                }

                // Identify debit and Monzo Flex accounts
                List<CompletableFuture<List<BankTransaction>>> futures = new ArrayList<>();

                for (MonzoAccountInfo mAcc : monzoAccounts) {
                    // Match to specific MonMon account (prioritizing matching name e.g. "Monzo" / "Flex")
                    Long monmonAccId = null;
                    if (accounts != null) {
                        if (mAcc.isFlexCreditCard()) {
                            // Find credit account named Flex or Monzo first
                            monmonAccId = accounts.stream()
                                    .filter(a -> a.getType() == Account.AccountType.CREDIT &&
                                            (a.getName().toLowerCase().contains("flex") || a.getName().toLowerCase().contains("monzo")))
                                    .map(Account::getId)
                                    .findFirst()
                                    .orElseGet(() -> accounts.stream()
                                            .filter(a -> a.getType() == Account.AccountType.CREDIT)
                                            .map(Account::getId)
                                            .findFirst()
                                            .orElse(null));
                        } else if (mAcc.isDebit()) {
                            // Find debit account named Monzo first
                            monmonAccId = accounts.stream()
                                    .filter(a -> a.getType() == Account.AccountType.DEBIT &&
                                            a.getName().toLowerCase().contains("monzo"))
                                    .map(Account::getId)
                                    .findFirst()
                                    .orElseGet(() -> accounts.stream()
                                            .filter(a -> a.getType() == Account.AccountType.DEBIT)
                                            .map(Account::getId)
                                            .findFirst()
                                            .orElse(null));
                        }
                    }

                    futures.add(fetchTransactions(userId, monmonAccId, conn.getAccessToken(), mAcc.id, daysBack).toCompletableFuture());
                }

                return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                        .thenCompose(v -> {
                            List<BankTransaction> allTx = new ArrayList<>();
                            for (CompletableFuture<List<BankTransaction>> f : futures) {
                                try {
                                    allTx.addAll(f.join());
                                } catch (Exception ignored) {
                                }
                            }

                            return bankTransactionRepository.saveAll(allTx).thenApply(savedCount -> {
                                List<String> details = monzoAccounts.stream()
                                        .map(m -> m.getDisplayName() + " (" + m.id + ")")
                                        .collect(Collectors.toList());
                                return SyncResult.ok(
                                        savedCount,
                                        "Successfully synced " + savedCount + " transactions across " + monzoAccounts.size() + " Monzo accounts (Debit & Monzo Flex).",
                                        details
                                );
                            });
                        });
            });
        }).exceptionally(err -> SyncResult.error("Monzo sync error: " + err.getMessage()));
    }

    @Override
    public CompletionStage<SyncResult> importStatement(String userId, Long accountId, String statementData, boolean rewriteHistory) {
        return statementParserService.importStatement(userId, accountId, statementData, rewriteHistory);
    }
}
