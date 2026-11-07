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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static helpers.ModelHelpers.findAlreadyPaid;
import static helpers.ModelHelpers.findYetToPay;

@Singleton
public class RustOutgoingRepository implements OutgoingRepository {

    private static final Logger logger = LoggerFactory.getLogger(RustOutgoingRepository.class);

    private final JPAOutgoingRepository jpaRepo;
    private final RustCoreClient rust;

    @Inject
    public RustOutgoingRepository(JPAOutgoingRepository jpaRepo, RustCoreClient rust) {
        this.jpaRepo = jpaRepo;
        this.rust = rust;
    }

    @Override
    public CompletionStage<Outgoing> add(Outgoing outgoing) {
        if (!rust.isEnabled()) {
            return jpaRepo.add(outgoing);
        }

        ObjectNode json = Json.newObject();
        json.put("name", outgoing.getName());
        json.put("cost", outgoing.getCost() != null ? outgoing.getCost() : 0.0f);
        json.put("outgoingDay", outgoing.getOutgoingDay());
        json.put("outgoingMonth", outgoing.getOutgoingMonth() != null ? outgoing.getOutgoingMonth() : 0);
        json.put("yearInterval", outgoing.getYearInterval() != null ? outgoing.getYearInterval() : 1);
        if (outgoing.getStartingYear() != null) json.put("startingYear", outgoing.getStartingYear());
        json.put("userId", outgoing.getUserId());
        if (outgoing.getAccount() != null && outgoing.getAccount().getId() != null) {
            json.put("accountId", outgoing.getAccount().getId());
        }
        json.put("bill", outgoing.isBill());
        json.put("rent", outgoing.isRent());
        json.put("hiddenFromTotal", outgoing.isHiddenFromTotal());

        long start = System.currentTimeMillis();
        return rust.post("/internal/outgoings", json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || (response.statusCode() != 200 && response.statusCode() != 201)) {
                        logger.warn("[RustBridge] POST /internal/outgoings failed ({}), falling back to JPA", ex != null ? ex.getMessage() : response.statusCode());
                        return jpaRepo.add(outgoing);
                    }
                    logger.info("[RustBridge] POST /internal/outgoings -> rust ({}) in {}ms", response.statusCode(), System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Outgoing>> list(String userId) {
        if (!rust.isEnabled()) {
            return jpaRepo.list(userId);
        }

        long start = System.currentTimeMillis();
        String encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return rust.get("/internal/outgoings?userId=" + encoded)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.list(userId);
                    }
                    logger.info("[RustBridge] GET /internal/outgoings -> rust (200) in {}ms", System.currentTimeMillis() - start);
                    List<Outgoing> list = new ArrayList<>();
                    for (JsonNode node : Json.parse(response.body())) {
                        list.add(mapJsonToOutgoing(node));
                    }
                    return CompletableFuture.completedFuture(list.stream());
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Outgoing>> listComplete(String userId) {
        return list(userId);
    }

    @Override
    public CompletionStage<Outgoing> update(int outgoingId, Outgoing outgoing) {
        if (!rust.isEnabled()) {
            return jpaRepo.update(outgoingId, outgoing);
        }

        ObjectNode json = Json.newObject();
        json.put("name", outgoing.getName());
        json.put("cost", outgoing.getCost() != null ? outgoing.getCost() : 0.0f);
        json.put("outgoingDay", outgoing.getOutgoingDay());
        json.put("outgoingMonth", outgoing.getOutgoingMonth() != null ? outgoing.getOutgoingMonth() : 0);
        json.put("yearInterval", outgoing.getYearInterval() != null ? outgoing.getYearInterval() : 1);
        if (outgoing.getStartingYear() != null) json.put("startingYear", outgoing.getStartingYear());
        if (outgoing.getAccount() != null && outgoing.getAccount().getId() != null) {
            json.put("accountId", outgoing.getAccount().getId());
        }
        json.put("bill", outgoing.isBill());
        json.put("rent", outgoing.isRent());
        json.put("hiddenFromTotal", outgoing.isHiddenFromTotal());

        long start = System.currentTimeMillis();
        return rust.put("/internal/outgoings/" + outgoingId, json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.update(outgoingId, outgoing);
                    }
                    logger.info("[RustBridge] PUT /internal/outgoings/{} -> rust in {}ms", outgoingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Outgoing> archive(int outgoingId) {
        if (!rust.isEnabled()) {
            return jpaRepo.archive(outgoingId);
        }

        long start = System.currentTimeMillis();
        return rust.post("/internal/outgoings/" + outgoingId + "/archive", "{}")
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.archive(outgoingId);
                    }
                    logger.info("[RustBridge] POST /internal/outgoings/{}/archive -> rust in {}ms", outgoingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Outgoing> pause(int outgoingId, String reason) {
        if (!rust.isEnabled()) {
            return jpaRepo.pause(outgoingId, reason);
        }

        ObjectNode json = Json.newObject();
        json.put("reason", reason != null ? reason : "");
        json.put("userId", "");

        long start = System.currentTimeMillis();
        return rust.post("/internal/outgoings/" + outgoingId + "/pause", json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.pause(outgoingId, reason);
                    }
                    logger.info("[RustBridge] POST /internal/outgoings/{}/pause -> rust in {}ms", outgoingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Outgoing> unpause(int outgoingId) {
        if (!rust.isEnabled()) {
            return jpaRepo.unpause(outgoingId);
        }

        ObjectNode json = Json.newObject();
        json.put("userId", "");

        long start = System.currentTimeMillis();
        return rust.post("/internal/outgoings/" + outgoingId + "/unpause", json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.unpause(outgoingId);
                    }
                    logger.info("[RustBridge] POST /internal/outgoings/{}/unpause -> rust in {}ms", outgoingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Outgoing> findById(int outgoingId) {
        if (!rust.isEnabled()) {
            return jpaRepo.findById(outgoingId);
        }

        long start = System.currentTimeMillis();
        return rust.get("/internal/outgoings/" + outgoingId)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.findById(outgoingId);
                    }
                    logger.info("[RustBridge] GET /internal/outgoings/{} -> rust in {}ms", outgoingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToOutgoing(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Outgoing>> rents(String userId) {
        if (!rust.isEnabled()) {
            return jpaRepo.rents(userId);
        }

        String encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return rust.get("/internal/outgoings/rents?userId=" + encoded)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.rents(userId);
                    }
                    List<Outgoing> list = new ArrayList<>();
                    for (JsonNode node : Json.parse(response.body())) {
                        list.add(mapJsonToOutgoing(node));
                    }
                    return CompletableFuture.completedFuture(list.stream());
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Outgoing>> bills(String userId) {
        if (!rust.isEnabled()) {
            return jpaRepo.bills(userId);
        }

        String encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return rust.get("/internal/outgoings/bills?userId=" + encoded)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.bills(userId);
                    }
                    List<Outgoing> list = new ArrayList<>();
                    for (JsonNode node : Json.parse(response.body())) {
                        list.add(mapJsonToOutgoing(node));
                    }
                    return CompletableFuture.completedFuture(list.stream());
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Outgoing>> alreadyPaid(LocalDate asOf, int paydayDay, String userId) {
        return list(userId).thenApply(outgoings -> {
            List<Outgoing> list = outgoings.collect(Collectors.toList());
            return findAlreadyPaid(list, asOf, paydayDay).stream();
        });
    }

    @Override
    public CompletionStage<Stream<Outgoing>> yetToPay(LocalDate asOf, int paydayDay, String userId) {
        return list(userId).thenApply(outgoings -> {
            List<Outgoing> list = outgoings.collect(Collectors.toList());
            return findYetToPay(list, asOf, paydayDay).stream();
        });
    }

    private Outgoing mapJsonToOutgoing(JsonNode node) {
        Outgoing o = new Outgoing();
        if (node.hasNonNull("id")) o.id = node.get("id").asLong();
        if (node.hasNonNull("name")) o.name = node.get("name").asText();
        if (node.hasNonNull("cost")) o.cost = (float) node.get("cost").asDouble();
        if (node.hasNonNull("outgoingDay")) o.outgoingDay = node.get("outgoingDay").asInt();
        if (node.hasNonNull("outgoingMonth")) o.outgoingMonth = node.get("outgoingMonth").asInt();
        if (node.hasNonNull("yearInterval")) o.yearInterval = node.get("yearInterval").asInt();
        if (node.hasNonNull("startingYear")) o.startingYear = node.get("startingYear").asInt();
        if (node.hasNonNull("bill")) o.bill = node.get("bill").asBoolean();
        if (node.hasNonNull("rent")) o.rent = node.get("rent").asBoolean();
        if (node.hasNonNull("hiddenFromTotal")) o.hiddenFromTotal = node.get("hiddenFromTotal").asBoolean();
        if (node.hasNonNull("paused")) o.paused = node.get("paused").asBoolean();
        if (node.hasNonNull("pauseReason")) o.pauseReason = node.get("pauseReason").asText();
        if (node.hasNonNull("pausedAt")) o.pausedAt = node.get("pausedAt").asLong();
        if (node.hasNonNull("userId")) o.userId = node.get("userId").asText();

        if (node.hasNonNull("accountId")) {
            Account acc = new Account();
            acc.id = node.get("accountId").asLong();
            if (node.hasNonNull("accountName")) acc.name = node.get("accountName").asText();
            if (node.hasNonNull("accountType")) {
                try {
                    acc.type = Account.AccountType.valueOf(node.get("accountType").asText());
                } catch (Exception ignored) {}
            }
            o.account = acc;
        }
        return o;
    }
}
