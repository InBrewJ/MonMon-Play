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
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.stream.Stream;

@Singleton
public class RustIncomingRepository implements IncomingRepository {

    private static final Logger logger = LoggerFactory.getLogger(RustIncomingRepository.class);

    private final JPAIncomingRepository jpaRepo;
    private final RustCoreClient rust;

    @Inject
    public RustIncomingRepository(JPAIncomingRepository jpaRepo, RustCoreClient rust) {
        this.jpaRepo = jpaRepo;
        this.rust = rust;
    }

    @Override
    public CompletionStage<Incoming> add(Incoming incoming) {
        if (!rust.isEnabled()) {
            return jpaRepo.add(incoming);
        }

        ObjectNode json = Json.newObject();
        json.put("name", incoming.name);
        json.put("netValue", incoming.netValue != null ? incoming.netValue : 0.0f);
        if (incoming.grossValue != null) json.put("grossValue", incoming.grossValue);
        json.put("incomingMonthDay", incoming.incomingMonthDay);
        json.put("payday", incoming.payDay);
        json.put("userId", incoming.userId);
        if (incoming.account != null && incoming.account.getId() != null) {
            json.put("accountId", incoming.account.getId());
        }

        long start = System.currentTimeMillis();
        return rust.post("/internal/incomings", json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || (response.statusCode() != 200 && response.statusCode() != 201)) {
                        return jpaRepo.add(incoming);
                    }
                    logger.info("[RustBridge] POST /internal/incomings -> rust ({}) in {}ms", response.statusCode(), System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToIncoming(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Incoming> archive(int incomingId) {
        if (!rust.isEnabled()) {
            return jpaRepo.archive(incomingId);
        }

        long start = System.currentTimeMillis();
        return rust.post("/internal/incomings/" + incomingId + "/archive", "{}")
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.archive(incomingId);
                    }
                    logger.info("[RustBridge] POST /internal/incomings/{}/archive -> rust in {}ms", incomingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToIncoming(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Incoming> update(int incomingId, Incoming incoming) {
        if (!rust.isEnabled()) {
            return jpaRepo.update(incomingId, incoming);
        }

        ObjectNode json = Json.newObject();
        json.put("name", incoming.name);
        json.put("netValue", incoming.netValue != null ? incoming.netValue : 0.0f);
        if (incoming.grossValue != null) json.put("grossValue", incoming.grossValue);
        json.put("incomingMonthDay", incoming.incomingMonthDay);
        json.put("payday", incoming.payDay);
        if (incoming.account != null && incoming.account.getId() != null) {
            json.put("accountId", incoming.account.getId());
        }

        long start = System.currentTimeMillis();
        return rust.put("/internal/incomings/" + incomingId, json.toString())
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.update(incomingId, incoming);
                    }
                    logger.info("[RustBridge] PUT /internal/incomings/{} -> rust in {}ms", incomingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToIncoming(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Incoming> findById(int incomingId) {
        if (!rust.isEnabled()) {
            return jpaRepo.findById(incomingId);
        }

        long start = System.currentTimeMillis();
        return rust.get("/internal/incomings/" + incomingId)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.findById(incomingId);
                    }
                    logger.info("[RustBridge] GET /internal/incomings/{} -> rust in {}ms", incomingId, System.currentTimeMillis() - start);
                    return CompletableFuture.completedFuture(mapJsonToIncoming(Json.parse(response.body())));
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Incoming>> list(String userId) {
        if (!rust.isEnabled()) {
            return jpaRepo.list(userId);
        }

        long start = System.currentTimeMillis();
        String encoded = URLEncoder.encode(userId, StandardCharsets.UTF_8);
        return rust.get("/internal/incomings?userId=" + encoded)
                .handle((response, ex) -> {
                    if (ex != null || response == null || response.statusCode() != 200) {
                        return jpaRepo.list(userId);
                    }
                    logger.info("[RustBridge] GET /internal/incomings -> rust (200) in {}ms", System.currentTimeMillis() - start);
                    List<Incoming> list = new ArrayList<>();
                    for (JsonNode node : Json.parse(response.body())) {
                        list.add(mapJsonToIncoming(node));
                    }
                    return CompletableFuture.completedFuture(list.stream());
                })
                .thenCompose(Function.identity());
    }

    @Override
    public CompletionStage<Stream<Incoming>> listComplete(String userId) {
        return list(userId);
    }

    @Override
    public int getNextPayDay(String userId) throws ExecutionException, InterruptedException {
        return jpaRepo.getNextPayDay(userId);
    }

    private Incoming mapJsonToIncoming(JsonNode node) {
        Incoming inc = new Incoming();
        if (node.hasNonNull("id")) inc.id = node.get("id").asLong();
        if (node.hasNonNull("name")) inc.name = node.get("name").asText();
        if (node.hasNonNull("netValue")) inc.netValue = (float) node.get("netValue").asDouble();
        if (node.hasNonNull("grossValue")) inc.grossValue = (float) node.get("grossValue").asDouble();
        if (node.hasNonNull("incomingMonthDay")) inc.incomingMonthDay = node.get("incomingMonthDay").asInt();
        if (node.hasNonNull("payday")) inc.payDay = node.get("payday").asBoolean();
        if (node.hasNonNull("userId")) inc.userId = node.get("userId").asText();
        if (node.hasNonNull("archived")) inc.archived = node.get("archived").asBoolean();

        if (node.hasNonNull("accountId")) {
            Account acc = new Account();
            acc.id = node.get("accountId").asLong();
            if (node.hasNonNull("accountName")) acc.name = node.get("accountName").asText();
            inc.account = acc;
        }
        return inc;
    }
}
