package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import models.Account;
import models.Balance;
import models.BalanceRepository;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.MonzoConnection;
import models.MonzoConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
    private final BalanceRepository balanceRepository;
    private final models.AccountRepository accountRepository;
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
                                 StatementParserService statementParserService,
                                 BalanceRepository balanceRepository,
                                 models.AccountRepository accountRepository) {
        this(config, bankTransactionRepository, monzoConnectionRepository, statementParserService, balanceRepository, accountRepository,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MonzoBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 MonzoConnectionRepository monzoConnectionRepository,
                                 StatementParserService statementParserService,
                                 BalanceRepository balanceRepository) {
        this(config, bankTransactionRepository, monzoConnectionRepository, statementParserService, balanceRepository, null,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MonzoBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 MonzoConnectionRepository monzoConnectionRepository,
                                 StatementParserService statementParserService) {
        this(config, bankTransactionRepository, monzoConnectionRepository, statementParserService, null, null,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    public MonzoBankSyncProvider(Config config,
                                 BankTransactionRepository bankTransactionRepository,
                                 MonzoConnectionRepository monzoConnectionRepository,
                                 StatementParserService statementParserService,
                                 BalanceRepository balanceRepository,
                                 models.AccountRepository accountRepository,
                                 HttpClient httpClient) {
        this.config = config;
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.statementParserService = statementParserService;
        this.balanceRepository = balanceRepository;
        this.accountRepository = accountRepository;
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

    public float convertToAvailableBalance(Account account, float monzoBalance) {
        if (account != null && account.getType() == Account.AccountType.CREDIT) {
            float creditLimit = account.getAvailableLimit() != null ? account.getAvailableLimit() : 0f;
            return (float) helpers.MathHelpers.round2(creditLimit - Math.abs(monzoBalance));
        }
        return monzoBalance;
    }

    public static class MonzoWebhookInfo {
        public final String id;
        public final String accountId;
        public final String url;

        public MonzoWebhookInfo(String id, String accountId, String url) {
            this.id = id;
            this.accountId = accountId;
            this.url = url;
        }
    }

    public static class LiveWebhookDetail {
        public final String accountId;
        public final String accountLabel;
        public final String webhookId;
        public final String url;

        public LiveWebhookDetail(String accountId, String accountLabel, String webhookId, String url) {
            this.accountId = accountId;
            this.accountLabel = accountLabel;
            this.webhookId = webhookId;
            this.url = url;
        }

        public String getAccountId() {
            return accountId;
        }

        public String getAccountLabel() {
            return accountLabel;
        }

        public String getWebhookId() {
            return webhookId;
        }

        public String getUrl() {
            return url;
        }
    }

    public static class WebhookSyncResult {
        private final boolean success;
        private final String message;
        private final String debitWebhookId;
        private final String flexWebhookId;
        private final List<String> details;

        public WebhookSyncResult(boolean success, String message, String debitWebhookId, String flexWebhookId, List<String> details) {
            this.success = success;
            this.message = message;
            this.debitWebhookId = debitWebhookId;
            this.flexWebhookId = flexWebhookId;
            this.details = details != null ? details : Collections.emptyList();
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public String getDebitWebhookId() {
            return debitWebhookId;
        }

        public String getFlexWebhookId() {
            return flexWebhookId;
        }

        public List<String> getDetails() {
            return details;
        }
    }

    private static class AccountWebhookSyncInfo {
        final String webhookId;
        final boolean newlyCreated;
        final int staleDeleted;
        final String summary;

        AccountWebhookSyncInfo(String webhookId, boolean newlyCreated, int staleDeleted, String summary) {
            this.webhookId = webhookId;
            this.newlyCreated = newlyCreated;
            this.staleDeleted = staleDeleted;
            this.summary = summary;
        }
    }

    public CompletionStage<MonzoConnection> registerWebhooks(MonzoConnection conn, String baseUrl) {
        return syncWebhooks(conn, baseUrl)
                .thenApply(res -> conn)
                .exceptionally(err -> conn);
    }

    public CompletionStage<List<LiveWebhookDetail>> discoverLiveWebhooks(MonzoConnection conn) {
        if (conn == null || conn.getAccessToken() == null || conn.getAccessToken().trim().isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        return fetchAccounts(conn.getAccessToken()).thenCompose(accounts -> {
            MonzoAccountInfo debitAcc = accounts.stream().filter(MonzoAccountInfo::isDebit).findFirst().orElse(null);
            MonzoAccountInfo flexAcc = accounts.stream().filter(MonzoAccountInfo::isFlexCreditCard).findFirst().orElse(null);

            CompletableFuture<List<MonzoWebhookInfo>> debitWhFuture = debitAcc != null
                    ? listMonzoWebhooks(conn.getAccessToken(), debitAcc.id)
                    : CompletableFuture.completedFuture(Collections.emptyList());

            CompletableFuture<List<MonzoWebhookInfo>> flexWhFuture = flexAcc != null
                    ? listMonzoWebhooks(conn.getAccessToken(), flexAcc.id)
                    : CompletableFuture.completedFuture(Collections.emptyList());

            return CompletableFuture.allOf(debitWhFuture, flexWhFuture).thenCompose(v -> {
                List<MonzoWebhookInfo> debitWhs = debitWhFuture.join();
                List<MonzoWebhookInfo> flexWhs = flexWhFuture.join();

                List<LiveWebhookDetail> details = new ArrayList<>();
                boolean changed = false;

                if (debitAcc != null && !debitWhs.isEmpty()) {
                    for (MonzoWebhookInfo wh : debitWhs) {
                        details.add(new LiveWebhookDetail(debitAcc.id, debitAcc.getDisplayName(), wh.id, wh.url));
                    }
                    MonzoWebhookInfo primaryDebit = debitWhs.get(0);
                    if (!primaryDebit.id.equals(conn.getWebhookDebitId())) {
                        conn.setWebhookDebitId(primaryDebit.id);
                        changed = true;
                    }
                    if (primaryDebit.url != null && !primaryDebit.url.equals(conn.getWebhookUrl())) {
                        conn.setWebhookUrl(primaryDebit.url);
                        changed = true;
                    }
                } else if (debitAcc != null && conn.getWebhookDebitId() != null) {
                    conn.setWebhookDebitId(null);
                    changed = true;
                }

                if (flexAcc != null && !flexWhs.isEmpty()) {
                    for (MonzoWebhookInfo wh : flexWhs) {
                        details.add(new LiveWebhookDetail(flexAcc.id, flexAcc.getDisplayName(), wh.id, wh.url));
                    }
                    MonzoWebhookInfo primaryFlex = flexWhs.get(0);
                    if (!primaryFlex.id.equals(conn.getWebhookFlexId())) {
                        conn.setWebhookFlexId(primaryFlex.id);
                        changed = true;
                    }
                    if (conn.getWebhookUrl() == null && primaryFlex.url != null) {
                        conn.setWebhookUrl(primaryFlex.url);
                        changed = true;
                    }
                } else if (flexAcc != null && conn.getWebhookFlexId() != null) {
                    conn.setWebhookFlexId(null);
                    changed = true;
                }

                if (changed) {
                    return monzoConnectionRepository.save(conn).thenApply(saved -> details);
                }
                return CompletableFuture.completedFuture(details);
            });
        }).exceptionally(err -> {
            System.err.println("Failed to discover live Monzo webhooks: " + err.getMessage());
            return Collections.emptyList();
        });
    }

    public CompletionStage<WebhookSyncResult> syncWebhooks(MonzoConnection conn, String baseUrl) {
        if (conn == null || conn.getAccessToken() == null || conn.getAccessToken().trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    new WebhookSyncResult(false, "No active Monzo connection or access token found.", null, null, Collections.emptyList()));
        }

        String trimmedBaseUrl = baseUrl != null ? baseUrl.replaceAll("/+$", "") : "";
        if (!trimmedBaseUrl.toLowerCase().startsWith("https://")) {
            return discoverLiveWebhooks(conn).thenApply(existingList -> {
                if (!existingList.isEmpty()) {
                    List<String> labels = new ArrayList<>();
                    for (LiveWebhookDetail d : existingList) {
                        labels.add(d.accountLabel + " (" + d.webhookId + " -> " + d.url + ")");
                    }
                    return new WebhookSyncResult(true,
                            "Discovered " + existingList.size() + " active webhook(s) already registered on Monzo! Linked successfully: " + String.join(", ", labels),
                            conn.getWebhookDebitId(), conn.getWebhookFlexId(), labels);
                }
                return new WebhookSyncResult(false,
                        "Monzo requires a public HTTPS webhook URL (starting with https://). Detected: '" + trimmedBaseUrl + "'. No existing webhooks were found on Monzo. If running locally, please supply an ngrok or Cloudflare tunnel URL.",
                        null, null, Collections.emptyList());
            });
        }

        String webhookCallbackUrl = trimmedBaseUrl + "/api/monzo/webhook?userId=" +
                URLEncoder.encode(conn.getUserId(), StandardCharsets.UTF_8);

        return fetchAccounts(conn.getAccessToken()).thenCompose(accounts -> {
            MonzoAccountInfo debitAcc = accounts.stream().filter(MonzoAccountInfo::isDebit).findFirst().orElse(null);
            MonzoAccountInfo flexAcc = accounts.stream().filter(MonzoAccountInfo::isFlexCreditCard).findFirst().orElse(null);

            if (debitAcc == null && flexAcc == null) {
                return CompletableFuture.completedFuture(
                        new WebhookSyncResult(false, "No compatible Monzo accounts (retail debit or flex) found.", null, null, Collections.emptyList()));
            }

            CompletableFuture<AccountWebhookSyncInfo> debitFuture = debitAcc != null
                    ? syncAccountWebhook(conn.getAccessToken(), debitAcc.id, "Debit Account (" + debitAcc.id + ")", webhookCallbackUrl)
                    : CompletableFuture.completedFuture(null);

            CompletableFuture<AccountWebhookSyncInfo> flexFuture = flexAcc != null
                    ? syncAccountWebhook(conn.getAccessToken(), flexAcc.id, "Flex Account (" + flexAcc.id + ")", webhookCallbackUrl)
                    : CompletableFuture.completedFuture(null);

            return CompletableFuture.allOf(debitFuture, flexFuture).thenCompose(v -> {
                AccountWebhookSyncInfo debitRes = debitFuture.join();
                AccountWebhookSyncInfo flexRes = flexFuture.join();

                List<String> details = new ArrayList<>();
                String debitWhId = debitRes != null ? debitRes.webhookId : null;
                String flexWhId = flexRes != null ? flexRes.webhookId : null;
                int totalStale = 0;

                if (debitRes != null) {
                    details.add(debitRes.summary);
                    totalStale += debitRes.staleDeleted;
                }
                if (flexRes != null) {
                    details.add(flexRes.summary);
                    totalStale += flexRes.staleDeleted;
                }
                if (totalStale > 0) {
                    details.add("Pruned " + totalStale + " stale MonMon webhook(s) to avoid duplicates.");
                }

                conn.setWebhookUrl(webhookCallbackUrl);
                if (debitWhId != null) {
                    conn.setWebhookDebitId(debitWhId);
                }
                if (flexWhId != null) {
                    conn.setWebhookFlexId(flexWhId);
                }

                boolean anyActive = debitWhId != null || flexWhId != null;
                String msg = anyActive
                        ? "Webhooks synchronized successfully! " + String.join(" | ", details)
                        : "Failed to register webhooks with Monzo. Monzo rejected the callback URL. Verify it is reachable from the public internet.";

                return monzoConnectionRepository.save(conn).thenApply(saved ->
                        new WebhookSyncResult(anyActive, msg, debitWhId, flexWhId, details)
                );
            });
        }).exceptionally(err -> {
            String errDetail = err.getMessage() != null ? err.getMessage() : err.toString();
            System.err.println("Failed to sync Monzo webhooks: " + errDetail);
            return new WebhookSyncResult(false, "Failed to sync Monzo webhooks: " + errDetail, null, null, Collections.emptyList());
        });
    }

    private CompletableFuture<AccountWebhookSyncInfo> syncAccountWebhook(
            String accessToken,
            String accountId,
            String accountLabel,
            String targetCallbackUrl) {

        return listMonzoWebhooks(accessToken, accountId).thenCompose(existingWebhooks -> {
            String matchingWebhookId = null;
            List<CompletableFuture<Boolean>> cleanupFutures = new ArrayList<>();

            for (MonzoWebhookInfo wh : existingWebhooks) {
                if (wh.url != null && wh.url.equals(targetCallbackUrl)) {
                    matchingWebhookId = wh.id;
                } else if (wh.url != null && wh.url.contains("/api/monzo/webhook")) {
                    // Stale MonMon webhook pointing to a different/previous URL
                    cleanupFutures.add(deleteMonzoWebhook(accessToken, wh.id));
                }
            }

            final String existingId = matchingWebhookId;
            return CompletableFuture.allOf(cleanupFutures.toArray(new CompletableFuture[0])).thenCompose(v -> {
                int cleanedUpCount = (int) cleanupFutures.stream()
                        .filter(f -> Boolean.TRUE.equals(f.getNow(false)))
                        .count();

                if (existingId != null) {
                    return CompletableFuture.completedFuture(
                            new AccountWebhookSyncInfo(existingId, false, cleanedUpCount,
                                    accountLabel + ": Reused existing webhook (" + existingId + ")"));
                }

                return createMonzoWebhook(accessToken, accountId, targetCallbackUrl).thenApply(newId -> {
                    if (newId != null) {
                        return new AccountWebhookSyncInfo(newId, true, cleanedUpCount,
                                accountLabel + ": Registered new webhook (" + newId + ")");
                    } else {
                        return new AccountWebhookSyncInfo(null, false, cleanedUpCount,
                                accountLabel + ": Failed to register webhook on Monzo");
                    }
                });
            });
        });
    }

    public CompletableFuture<List<MonzoWebhookInfo>> listMonzoWebhooks(String accessToken, String accountId) {
        String uri = MONZO_API_BASE + "/webhooks?account_id=" + URLEncoder.encode(accountId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    List<MonzoWebhookInfo> list = new ArrayList<>();
                    if (response.statusCode() == 200) {
                        try {
                            JsonNode root = mapper.readTree(response.body());
                            JsonNode webhooksNode = root.path("webhooks");
                            if (webhooksNode.isArray()) {
                                for (JsonNode wh : webhooksNode) {
                                    String id = wh.path("id").asText();
                                    String accId = wh.path("account_id").asText();
                                    String url = wh.path("url").asText();
                                    if (id != null && !id.isEmpty()) {
                                        list.add(new MonzoWebhookInfo(id, accId, url));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            System.err.println("Failed to parse Monzo webhooks: " + e.getMessage());
                        }
                    } else {
                        System.err.println("Monzo list webhooks returned status " + response.statusCode() + ": " + response.body());
                    }
                    return list;
                }).toCompletableFuture();
    }

    public CompletableFuture<Boolean> deleteMonzoWebhook(String accessToken, String webhookId) {
        if (webhookId == null || webhookId.trim().isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        String uri = MONZO_API_BASE + "/webhooks/" + URLEncoder.encode(webhookId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(uri))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .DELETE()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> response.statusCode() >= 200 && response.statusCode() < 300)
                .exceptionally(err -> false)
                .toCompletableFuture();
    }

    public CompletionStage<Boolean> unregisterWebhooks(MonzoConnection conn) {
        if (conn == null || conn.getAccessToken() == null || conn.getAccessToken().trim().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }
        List<CompletableFuture<Boolean>> futures = new ArrayList<>();
        if (conn.getWebhookDebitId() != null && !conn.getWebhookDebitId().isEmpty()) {
            futures.add(deleteMonzoWebhook(conn.getAccessToken(), conn.getWebhookDebitId()));
        }
        if (conn.getWebhookFlexId() != null && !conn.getWebhookFlexId().isEmpty()) {
            futures.add(deleteMonzoWebhook(conn.getAccessToken(), conn.getWebhookFlexId()));
        }
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> true)
                .exceptionally(err -> true);
    }

    private CompletableFuture<String> createMonzoWebhook(String accessToken, String accountId, String callbackUrl) {
        String formBody = "account_id=" + URLEncoder.encode(accountId, StandardCharsets.UTF_8) +
                "&url=" + URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(MONZO_API_BASE + "/webhooks"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() == 200 || response.statusCode() == 201) {
                        try {
                            JsonNode json = mapper.readTree(response.body());
                            return json.path("webhook").path("id").asText(null);
                        } catch (Exception e) {
                            return null;
                        }
                    }
                    System.err.println("Monzo webhook creation returned status " + response.statusCode() + ": " + response.body());
                    return null;
                }).toCompletableFuture();
    }

    public CompletionStage<Boolean> handleWebhookEvent(String userId, JsonNode payload) {
        if (payload == null) {
            return CompletableFuture.completedFuture(false);
        }

        String eventType = payload.path("type").asText("");
        if (!"transaction.created".equalsIgnoreCase(eventType)) {
            // Test webhook ping or unhandled event type
            return CompletableFuture.completedFuture(true);
        }

        JsonNode data = payload.get("data");
        if (data == null || data.isNull()) {
            return CompletableFuture.completedFuture(false);
        }

        // Skip declined transactions
        if (data.path("decline_reason").isTextual() && !data.path("decline_reason").asText().isEmpty()) {
            return CompletableFuture.completedFuture(true);
        }

        String txId = data.path("id").asText();
        String monzoAccId = data.path("account_id").asText();
        long amountPence = data.path("amount").asLong(0L);

        // MonMon expense convention: spend is positive float, credit/deposit is negative float
        float amountPounds = Math.abs(amountPence) / 100.0f;
        if (amountPence > 0) {
            amountPounds = -amountPounds;
        }

        String createdStr = data.path("created").asText();
        long epochSec = Instant.now().getEpochSecond();
        String dateStr = LocalDate.now().toString();
        try {
            Instant inst = Instant.parse(createdStr);
            epochSec = inst.getEpochSecond();
            dateStr = inst.atZone(ZoneOffset.UTC).toLocalDate().toString();
        } catch (Exception ignored) {
        }

        String description = data.path("description").asText("Monzo Transaction");
        String merchantName = null;
        String category = data.path("category").asText("General");
        String merchantAddress = null;
        Double latitude = null;
        Double longitude = null;
        String merchantEmoji = null;

        JsonNode merchantNode = data.get("merchant");
        if (merchantNode != null && !merchantNode.isNull()) {
            merchantName = merchantNode.path("name").asText(null);
            if (merchantName != null && !merchantName.trim().isEmpty()) {
                description = merchantName;
            }
            if (merchantNode.has("category") && !merchantNode.path("category").asText().isEmpty()) {
                category = merchantNode.path("category").asText();
            }
            if (merchantNode.has("emoji") && !merchantNode.path("emoji").asText().isEmpty()) {
                merchantEmoji = merchantNode.path("emoji").asText();
            }
            JsonNode addressNode = merchantNode.get("address");
            if (addressNode != null && !addressNode.isNull()) {
                if (addressNode.has("formatted") && !addressNode.path("formatted").asText().isEmpty()) {
                    merchantAddress = addressNode.path("formatted").asText();
                } else if (addressNode.has("short_formatted")) {
                    merchantAddress = addressNode.path("short_formatted").asText();
                }
                if (addressNode.has("latitude") && addressNode.get("latitude").isNumber()) {
                    latitude = addressNode.get("latitude").asDouble();
                }
                if (addressNode.has("longitude") && addressNode.get("longitude").isNumber()) {
                    longitude = addressNode.get("longitude").asDouble();
                }
            }
        }

        boolean isFlex = data.path("metadata").path("is_flex_direct_virtual_card_tx").asBoolean(false)
                || data.path("metadata").has("monzo_flex_id")
                || data.path("metadata").has("backing_loan_id");

        CompletionStage<Optional<MonzoConnection>> connFuture = (userId != null && !userId.trim().isEmpty())
                ? monzoConnectionRepository.findByUserId(userId)
                : monzoConnectionRepository.findFirst();

        final String finalDesc = description;
        final String finalMerchant = merchantName;
        final String finalCategory = category;
        final String finalAddress = merchantAddress;
        final Double finalLat = latitude;
        final Double finalLon = longitude;
        final String finalEmoji = merchantEmoji;
        final float finalAmount = amountPounds;
        final long finalEpochSec = epochSec;
        final String finalDateStr = dateStr;
        String rawDataStr = null;
        try {
            rawDataStr = mapper.writeValueAsString(data);
        } catch (Exception e) {
            logger.warn("Could not serialize Monzo webhook data to JSON string", e);
        }
        final String finalRawData = rawDataStr;

        return connFuture.thenCompose(connOpt -> {
            if (connOpt.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            MonzoConnection conn = connOpt.get();
            String effectiveUserId = conn.getUserId();

            CompletionStage<List<Account>> accFuture = (accountRepository != null)
                    ? accountRepository.list(effectiveUserId).thenApply(s -> s.collect(Collectors.toList()))
                    : CompletableFuture.completedFuture(Collections.emptyList());

            return accFuture.thenCompose(accounts -> {
                Account matchedAcc = null;
                if (!accounts.isEmpty()) {
                    if (isFlex) {
                        matchedAcc = accounts.stream()
                                .filter(a -> a.getType() == Account.AccountType.CREDIT &&
                                        (a.getName().toLowerCase().contains("flex") || a.getName().toLowerCase().contains("monzo")))
                                .findFirst()
                                .orElseGet(() -> accounts.stream()
                                        .filter(a -> a.getType() == Account.AccountType.CREDIT)
                                        .findFirst()
                                        .orElse(accounts.get(0)));
                    } else {
                        matchedAcc = accounts.stream()
                                .filter(a -> a.getType() == Account.AccountType.DEBIT &&
                                        a.getName().toLowerCase().contains("monzo"))
                                .findFirst()
                                .orElseGet(() -> accounts.stream()
                                        .filter(a -> a.getType() == Account.AccountType.DEBIT)
                                        .findFirst()
                                        .orElse(accounts.get(0)));
                    }
                }

                Long accountId = matchedAcc != null ? matchedAcc.getId() : null;
                BankTransaction tx = new BankTransaction(
                        effectiveUserId,
                        accountId,
                        txId,
                        finalEpochSec,
                        finalDateStr,
                        finalAmount,
                        finalDesc,
                        finalMerchant,
                        finalCategory,
                        "MONZO",
                        false
                );
                tx.setMerchantAddress(finalAddress);
                tx.setLatitude(finalLat);
                tx.setLongitude(finalLon);
                tx.setMerchantEmoji(finalEmoji);
                tx.setRawData(finalRawData);

                final Account targetAccount = matchedAcc;
                return bankTransactionRepository.save(tx).thenCompose(savedTx -> {
                    // Refresh balance immediately for ABL/TBL balance tracking
                    if (balanceRepository != null && targetAccount != null && monzoAccId != null && !monzoAccId.isEmpty()) {
                        return fetchBalance(conn.getAccessToken(), monzoAccId).thenCompose(balVal -> {
                            if (balVal != null) {
                                float availableVal = convertToAvailableBalance(targetAccount, balVal);
                                Balance bal = new Balance();
                                bal.setUserId(effectiveUserId);
                                bal.setValue(availableVal);
                                bal.setAccount(targetAccount);
                                bal.setTimestamp(Instant.now().getEpochSecond());
                                return balanceRepository.add(bal).thenApply(b -> true);
                            }
                            return CompletableFuture.completedFuture(true);
                        });
                    }
                    return CompletableFuture.completedFuture(true);
                }).thenCompose(v -> {
                    conn.setLastReceivedAt(Instant.now().getEpochSecond());
                    return monzoConnectionRepository.save(conn).thenApply(c -> true);
                });
            });
        }).exceptionally(err -> {
            System.err.println("Error processing Monzo webhook: " + err.getMessage());
            return false;
        });
    }

    public CompletionStage<Float> fetchBalance(String accessToken, String monzoAccountId) {
        String url = MONZO_API_BASE + "/balance?account_id=" + monzoAccountId;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        return null;
                    }
                    try {
                        JsonNode json = mapper.readTree(response.body());
                        long balancePence = json.path("balance").asLong(0L);
                        return balancePence / 100.0f;
                    } catch (Exception e) {
                        return null;
                    }
                });
    }

    public CompletionStage<List<BankTransaction>> fetchTransactions(String userId, Long accountId, String accessToken, String monzoAccountId, int daysBack) {
        int effectiveDays = daysBack > 0 ? daysBack : 90;
        Instant sinceInstant = Instant.now().minus(Duration.ofDays(effectiveDays));
        String sinceIso = DateTimeFormatter.ISO_INSTANT.format(sinceInstant);

        List<BankTransaction> accumulated = new ArrayList<>();
        return fetchTransactionsPage(userId, accountId, accessToken, monzoAccountId, sinceIso, accumulated, 0);
    }

    private CompletionStage<List<BankTransaction>> fetchTransactionsPage(
            String userId, Long accountId, String accessToken, String monzoAccountId, String since,
            List<BankTransaction> accumulated, int pageDepth) {

        if (pageDepth >= 10) { // Safety cap: max 10 pages * 100 = 1000 transactions
            return CompletableFuture.completedFuture(accumulated);
        }

        String encodedSince = URLEncoder.encode(since, StandardCharsets.UTF_8);
        String url = MONZO_API_BASE + "/transactions?account_id=" + monzoAccountId + "&expand[]=merchant&limit=100&since=" + encodedSince;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + accessToken)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenCompose(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Monzo transactions error (" + response.statusCode() + "): " + response.body());
                    }
                    try {
                        JsonNode json = mapper.readTree(response.body());
                        JsonNode txArray = json.get("transactions");
                        if (txArray == null || !txArray.isArray() || txArray.size() == 0) {
                            return CompletableFuture.completedFuture(accumulated);
                        }

                        List<BankTransaction> page = parseTransactionsJson(userId, accountId, txArray);
                        accumulated.addAll(page);

                        // If page reached max 100, paginate forward using the last transaction ID
                        if (txArray.size() >= 100) {
                            String lastTxId = txArray.get(txArray.size() - 1).path("id").asText();
                            if (lastTxId != null && !lastTxId.trim().isEmpty()) {
                                return fetchTransactionsPage(userId, accountId, accessToken, monzoAccountId, lastTxId, accumulated, pageDepth + 1);
                            }
                        }

                        return CompletableFuture.completedFuture(accumulated);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to parse Monzo transactions: " + e.getMessage(), e);
                    }
                });
    }

    private List<BankTransaction> parseTransactionsJson(String userId, Long accountId, JsonNode txArray) {
        List<BankTransaction> list = new ArrayList<>();
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
            String merchantAddress = null;
            Double latitude = null;
            Double longitude = null;
            String merchantEmoji = null;

            JsonNode merchantNode = tx.get("merchant");
            if (merchantNode != null && !merchantNode.isNull()) {
                merchantName = merchantNode.path("name").asText(null);
                if (merchantName != null && !merchantName.trim().isEmpty()) {
                    description = merchantName;
                }
                if (merchantNode.has("category") && !merchantNode.path("category").asText().isEmpty()) {
                    category = merchantNode.path("category").asText();
                }
                if (merchantNode.has("emoji") && !merchantNode.path("emoji").asText().isEmpty()) {
                    merchantEmoji = merchantNode.path("emoji").asText();
                }
                JsonNode addressNode = merchantNode.get("address");
                if (addressNode != null && !addressNode.isNull()) {
                    if (addressNode.has("formatted") && !addressNode.path("formatted").asText().isEmpty()) {
                        merchantAddress = addressNode.path("formatted").asText();
                    } else if (addressNode.has("short_formatted")) {
                        merchantAddress = addressNode.path("short_formatted").asText();
                    }
                    if (addressNode.has("latitude") && addressNode.get("latitude").isNumber()) {
                        latitude = addressNode.get("latitude").asDouble();
                    }
                    if (addressNode.has("longitude") && addressNode.get("longitude").isNumber()) {
                        longitude = addressNode.get("longitude").asDouble();
                    }
                }
            }

            boolean declined = tx.path("decline_reason").isTextual();
            if (declined) {
                continue; // Skip declined transactions
            }

            BankTransaction bankTx = new BankTransaction(
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
            );
            bankTx.setMerchantAddress(merchantAddress);
            bankTx.setLatitude(latitude);
            bankTx.setLongitude(longitude);
            bankTx.setMerchantEmoji(merchantEmoji);
            try {
                bankTx.setRawData(mapper.writeValueAsString(tx));
            } catch (Exception ignored) {
            }
            list.add(bankTx);
        }
        return list;
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
                return CompletableFuture.completedFuture(SyncResult.error("No Monzo connection found. Enter your Personal Access Token."));
            }
            MonzoConnection conn = connOpt.get();
            return fetchAccounts(conn.getAccessToken()).thenCompose(monzoAccounts -> {
                if (monzoAccounts.isEmpty()) {
                    return CompletableFuture.completedFuture(SyncResult.error("No active accounts found on your Monzo profile."));
                }
                MonzoAccountInfo targetCandidate = null;
                if (account.getType() == Account.AccountType.CREDIT) {
                    targetCandidate = monzoAccounts.stream().filter(MonzoAccountInfo::isFlexCreditCard).findFirst().orElse(null);
                }
                if (targetCandidate == null) {
                    targetCandidate = monzoAccounts.stream().filter(MonzoAccountInfo::isDebit).findFirst().orElse(monzoAccounts.get(0));
                }
                final MonzoAccountInfo target = targetCandidate;

                if (balanceRepository != null) {
                    return fetchBalance(conn.getAccessToken(), target.id).thenCompose(balVal -> {
                        if (balVal != null) {
                            float availableVal = convertToAvailableBalance(account, balVal);
                            Balance bal = new Balance();
                            bal.setUserId(account.getUserId());
                            bal.setValue(availableVal);
                            bal.setAccount(account);
                            bal.setTimestamp(Instant.now().getEpochSecond());
                            String msg = (account.getType() == Account.AccountType.CREDIT)
                                    ? "Available balance updated (£" + String.format("%.2f", availableVal) + " available from limit £" + String.format("%.2f", account.getAvailableLimit()) + ") for " + account.getName() + "."
                                    : "Balance refreshed (£" + String.format("%.2f", availableVal) + ") for " + account.getName() + ".";
                            return balanceRepository.add(bal).thenApply(b ->
                                    SyncResult.ok(1, msg + " Webhooks stream transactions automatically in real time."));
                        }
                        return CompletableFuture.completedFuture(SyncResult.ok(0, "Monzo connected. Transactions stream automatically via webhooks."));
                    });
                }
                return CompletableFuture.completedFuture(SyncResult.ok(0, "Monzo connected. Transactions stream automatically via webhooks."));
            });
        }).exceptionally(err -> SyncResult.error("Monzo error: " + err.getMessage()));
    }

    @Override
    public CompletionStage<SyncResult> syncAllUserAccounts(String userId, List<Account> accounts, int daysBack) {
        return monzoConnectionRepository.findByUserId(userId).thenCompose(connOpt -> {
            if (connOpt.isEmpty()) {
                return CompletableFuture.completedFuture(SyncResult.error(
                        "No Monzo connection active. Enter your Monzo Personal Access Token on the dashboard."));
            }
            MonzoConnection conn = connOpt.get();

            return fetchAccounts(conn.getAccessToken()).thenCompose(monzoAccounts -> {
                if (monzoAccounts.isEmpty()) {
                    return CompletableFuture.completedFuture(SyncResult.ok(0, "No Monzo accounts found."));
                }

                List<CompletableFuture<Void>> balFutures = new ArrayList<>();
                for (MonzoAccountInfo mAcc : monzoAccounts) {
                    Account matched = null;
                    if (accounts != null) {
                        if (mAcc.isFlexCreditCard()) {
                            matched = accounts.stream().filter(a -> a.getType() == Account.AccountType.CREDIT &&
                                    (a.getName().toLowerCase().contains("flex") || a.getName().toLowerCase().contains("monzo"))).findFirst().orElse(null);
                        } else if (mAcc.isDebit()) {
                            matched = accounts.stream().filter(a -> a.getType() == Account.AccountType.DEBIT &&
                                    a.getName().toLowerCase().contains("monzo")).findFirst().orElse(null);
                        }
                    }
                    if (balanceRepository != null && matched != null) {
                        final Account targetAcc = matched;
                        balFutures.add(fetchBalance(conn.getAccessToken(), mAcc.id).thenCompose(balVal -> {
                            if (balVal != null) {
                                float availableVal = convertToAvailableBalance(targetAcc, balVal);
                                Balance bal = new Balance();
                                bal.setUserId(userId);
                                bal.setValue(availableVal);
                                bal.setAccount(targetAcc);
                                bal.setTimestamp(Instant.now().getEpochSecond());
                                return balanceRepository.add(bal).thenAccept(b -> {});
                            }
                            return CompletableFuture.completedFuture((Void) null);
                        }).toCompletableFuture());
                    }
                }

                return CompletableFuture.allOf(balFutures.toArray(new CompletableFuture[0])).thenApply(v ->
                        SyncResult.ok(monzoAccounts.size(), "Live balances refreshed for " + monzoAccounts.size() + " Monzo accounts. Webhooks stream transactions automatically in real time."));
            });
        }).exceptionally(err -> SyncResult.error("Monzo refresh error: " + err.getMessage()));
    }

    @Override
    public CompletionStage<SyncResult> importStatement(String userId, Long accountId, String statementData, boolean rewriteHistory) {
        return statementParserService.importStatement(userId, accountId, statementData, rewriteHistory);
    }
}
