package models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.libs.Json;
import services.RustCoreClient;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

@Singleton
public class RustAccountRepository implements AccountRepository {

    private static final Logger logger = LoggerFactory.getLogger(RustAccountRepository.class);

    private final JPAAccountRepository jpaRepo;
    private final RustCoreClient rust;

    @Inject
    public RustAccountRepository(JPAAccountRepository jpaRepo, RustCoreClient rust) {
        this.jpaRepo = jpaRepo;
        this.rust = rust;
    }

    @Override
    public CompletionStage<Account> add(Account account) {
        if (!rust.isEnabled()) {
            return jpaRepo.add(account);
        }

        ObjectNode json = Json.newObject();
        json.put("name", account.getName());
        if (account.getNickname() != null) json.put("nickname", account.getNickname());
        json.put("type", account.getType() != null ? account.getType().name() : "DEBIT");
        json.put("userId", account.getUserId());
        json.put("accountLimit", account.getAvailableLimit() != null ? account.getAvailableLimit() : 0.0f);
        json.put("interestRate", account.getInterestRate() != null ? account.getInterestRate() : 0.0f);

        long start = System.currentTimeMillis();
        return rust.post("/internal/accounts", json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || (response.statusCode() != 200 && response.statusCode() != 201)) {
                        logger.warn("[RustBridge] POST /internal/accounts failed ({}), falling back to JPA", ex != null ? ex.getMessage() : response.statusCode());
                        return jpaRepo.add(account);
                    }
                    logger.info("[RustBridge] POST /internal/accounts -> rust ({}) in {}ms", response.statusCode(), System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToAccount(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Account>> list(String userId) {
        if (!rust.isEnabled()) {
            return jpaRepo.list(userId);
        }

        long start = System.currentTimeMillis();
        String encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return rust.get("/internal/accounts?userId=" + encoded)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        logger.warn("[RustBridge] GET /internal/accounts failed ({}), falling back to JPA", ex != null ? ex.getMessage() : response.statusCode());
                        return jpaRepo.list(userId);
                    }
                    logger.info("[RustBridge] GET /internal/accounts -> rust (200) in {}ms", System.currentTimeMillis() - start);
                    JsonNode arrayNode = Json.parse(response.body());
                    List<Account> accounts = new ArrayList<>();
                    for (JsonNode node : arrayNode) {
                        accounts.add(mapJsonToAccount(node));
                    }
                    return CompletableFuture.completedFuture(accounts.stream());
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Account>> listComplete(String userId) {
        return list(userId);
    }

    @Override
    public CompletionStage<Account> archive(int accountId) {
        if (!rust.isEnabled()) {
            return jpaRepo.archive(accountId);
        }

        long start = System.currentTimeMillis();
        return rust.post("/internal/accounts/" + accountId + "/archive", "{}")
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.archive(accountId);
                    }
                    logger.info("[RustBridge] POST /internal/accounts/{}/archive -> rust in {}ms", accountId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToAccount(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Account> findById(int accountId) {
        if (!rust.isEnabled()) {
            return jpaRepo.findById(accountId);
        }

        long start = System.currentTimeMillis();
        return rust.get("/internal/accounts/" + accountId)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.findById(accountId);
                    }
                    logger.info("[RustBridge] GET /internal/accounts/{} -> rust in {}ms", accountId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToAccount(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Account> update(int accountId, Account account) {
        if (!rust.isEnabled()) {
            return jpaRepo.update(accountId, account);
        }

        ObjectNode json = Json.newObject();
        json.put("name", account.getName());
        if (account.getNickname() != null) json.put("nickname", account.getNickname());
        json.put("type", account.getType() != null ? account.getType().name() : "DEBIT");
        json.put("accountLimit", account.getAvailableLimit() != null ? account.getAvailableLimit() : 0.0f);
        json.put("interestRate", account.getInterestRate() != null ? account.getInterestRate() : 0.0f);

        long start = System.currentTimeMillis();
        return rust.put("/internal/accounts/" + accountId, json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.update(accountId, account);
                    }
                    logger.info("[RustBridge] PUT /internal/accounts/{} -> rust in {}ms", accountId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToAccount(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Account> toggleLedgerMode(Long accountId) {
        if (!rust.isEnabled()) {
            return jpaRepo.toggleLedgerMode(accountId);
        }

        long start = System.currentTimeMillis();
        return rust.post("/internal/accounts/" + accountId + "/toggleLedger", "{}")
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.toggleLedgerMode(accountId);
                    }
                    logger.info("[RustBridge] POST /internal/accounts/{}/toggleLedger -> rust in {}ms", accountId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToAccount(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    private Account mapJsonToAccount(JsonNode node) {
        Account acc = new Account();
        if (node.hasNonNull("id")) acc.id = node.get("id").asLong();
        if (node.hasNonNull("name")) acc.name = node.get("name").asText();
        if (node.hasNonNull("nickname")) acc.nickname = node.get("nickname").asText();
        if (node.hasNonNull("userId")) acc.userId = node.get("userId").asText();
        if (node.hasNonNull("type")) {
            try {
                acc.type = Account.AccountType.valueOf(node.get("type").asText());
            } catch (Exception ignored) {}
        }
        if (node.hasNonNull("accountLimit")) acc.availableLimit = (float) node.get("accountLimit").asDouble();
        if (node.hasNonNull("interestRate")) acc.interestRate = (float) node.get("interestRate").asDouble();
        if (node.hasNonNull("ledgerMode")) {
            try {
                acc.ledgerMode = Account.LedgerMode.valueOf(node.get("ledgerMode").asText());
            } catch (Exception ignored) {}
        }
        if (node.hasNonNull("archived")) acc.archived = node.get("archived").asBoolean();
        return acc;
    }
}
