package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@SuppressWarnings("unchecked")
public class JPAMonzoConnectionRepository implements MonzoConnectionRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private volatile boolean schemaEnsured = false;

    @Inject
    public JPAMonzoConnectionRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema(jpaApi);
    }

    private void ensureSchema(JPAApi jpaApi) {
        if (!schemaEnsured) {
            synchronized (JPAMonzoConnectionRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS monzo_connection (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "userid VARCHAR(255) UNIQUE, " +
                                        "user_id VARCHAR(255), " +
                                        "accesstoken VARCHAR(1000), " +
                                        "access_token VARCHAR(1000), " +
                                        "refreshtoken VARCHAR(1000), " +
                                        "refresh_token VARCHAR(1000), " +
                                        "monzouserid VARCHAR(255), " +
                                        "monzo_user_id VARCHAR(255), " +
                                        "expiresat BIGINT, " +
                                        "expires_at BIGINT, " +
                                        "createdat BIGINT, " +
                                        "created_at BIGINT, " +
                                        "lastsyncedat BIGINT, " +
                                        "last_synced_at BIGINT" +
                                        ")",
                                "CREATE INDEX IF NOT EXISTS idx_mc_user ON monzo_connection (userid)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS userid VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS accesstoken VARCHAR(1000)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS refreshtoken VARCHAR(1000)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS monzouserid VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS expiresat BIGINT",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS createdat BIGINT",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS lastsyncedat BIGINT",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhookdebitid VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhookflexid VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhookurl VARCHAR(500)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS lastreceivedat BIGINT",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS clientid VARCHAR(500)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS clientsecret VARCHAR(1000)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS redirecturi VARCHAR(500)",
                                "ALTER TABLE monzo_connection ALTER COLUMN accesstoken DROP NOT NULL",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhook_debit_id VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhook_flex_id VARCHAR(255)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS webhook_url VARCHAR(500)",
                                "ALTER TABLE monzo_connection ADD COLUMN IF NOT EXISTS last_received_at BIGINT"
                        };
                        jpaApi.withTransaction(em -> {
                            for (String sql : statements) {
                                try {
                                    em.createNativeQuery(sql).executeUpdate();
                                } catch (Exception ignored) {
                                }
                            }
                        });
                        schemaEnsured = true;
                    } catch (Exception e) {
                        schemaEnsured = true;
                    }
                }
            }
        }
    }

    @Override
    public CompletionStage<MonzoConnection> save(MonzoConnection connection) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<MonzoConnection> existing = em.createQuery(
                        "select m from MonzoConnection m where m.userId = :userId", MonzoConnection.class)
                        .setParameter("userId", connection.getUserId())
                        .getResultList();
                if (!existing.isEmpty()) {
                    MonzoConnection prev = existing.get(0);
                    if (connection.getAccessToken() != null) {
                        prev.setAccessToken(connection.getAccessToken());
                    }
                    if (connection.getRefreshToken() != null) {
                        prev.setRefreshToken(connection.getRefreshToken());
                    }
                    if (connection.getMonzoUserId() != null) {
                        prev.setMonzoUserId(connection.getMonzoUserId());
                    }
                    if (connection.getExpiresAt() != null) {
                        prev.setExpiresAt(connection.getExpiresAt());
                    }
                    prev.setWebhookDebitId(connection.getWebhookDebitId());
                    prev.setWebhookFlexId(connection.getWebhookFlexId());
                    if (connection.getWebhookUrl() != null) {
                        prev.setWebhookUrl(connection.getWebhookUrl());
                    }
                    if (connection.getLastReceivedAt() != null) {
                        prev.setLastReceivedAt(connection.getLastReceivedAt());
                    }
                    if (connection.getClientId() != null && !connection.getClientId().trim().isEmpty()) {
                        prev.setClientId(connection.getClientId().trim());
                    }
                    if (connection.getClientSecret() != null && !connection.getClientSecret().trim().isEmpty()) {
                        prev.setClientSecret(connection.getClientSecret().trim());
                    }
                    if (connection.getRedirectUri() != null && !connection.getRedirectUri().trim().isEmpty()) {
                        prev.setRedirectUri(connection.getRedirectUri().trim());
                    }
                    prev.setLastSyncedAt(System.currentTimeMillis());
                    return em.merge(prev);
                }
            } catch (Exception ignored) {
            }

            if (connection.getId() == null) {
                em.persist(connection);
                return connection;
            } else {
                return em.merge(connection);
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<MonzoConnection>> findByUserId(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<MonzoConnection> list = em.createQuery(
                        "select m from MonzoConnection m where m.userId = :userId", MonzoConnection.class)
                        .setParameter("userId", userId)
                        .getResultList();
                return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
            } catch (Exception e) {
                return Optional.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<MonzoConnection>> findFirst() {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<MonzoConnection> list = em.createQuery(
                        "select m from MonzoConnection m order by m.id desc", MonzoConnection.class)
                        .setMaxResults(1)
                        .getResultList();
                return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
            } catch (Exception e) {
                return Optional.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Boolean> deleteByUserId(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                int count = em.createQuery("delete from MonzoConnection m where m.userId = :userId")
                        .setParameter("userId", userId)
                        .executeUpdate();
                return count > 0;
            } catch (Exception e) {
                return false;
            }
        }), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }
}
