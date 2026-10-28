package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@SuppressWarnings("unchecked")
public class JPAPlaidItemRepository implements PlaidItemRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private static volatile boolean schemaEnsured = false;

    @Inject
    public JPAPlaidItemRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema(jpaApi);
    }

    private static void ensureSchema(JPAApi jpaApi) {
        if (!schemaEnsured) {
            synchronized (JPAPlaidItemRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS plaid_item (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "user_id VARCHAR(255), " +
                                        "item_id VARCHAR(255) UNIQUE, " +
                                        "access_token VARCHAR(500), " +
                                        "institution_id VARCHAR(255), " +
                                        "institution_name VARCHAR(255), " +
                                        "cursor VARCHAR(255), " +
                                        "created_at BIGINT, " +
                                        "last_synced_at BIGINT" +
                                        ")",
                                "CREATE INDEX IF NOT EXISTS idx_pi_user ON plaid_item (user_id)"
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
    public CompletionStage<PlaidItem> save(PlaidItem item) {
        return supplyAsync(() -> wrap(em -> {
            if (item.getId() == null) {
                em.persist(item);
                return item;
            } else {
                return em.merge(item);
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<List<PlaidItem>> findByUserId(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                return em.createQuery("select p from PlaidItem p where p.userId = :userId", PlaidItem.class)
                        .setParameter("userId", userId)
                        .getResultList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<PlaidItem> findByItemId(String itemId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<PlaidItem> list = em.createQuery("select p from PlaidItem p where p.itemId = :itemId", PlaidItem.class)
                        .setParameter("itemId", itemId)
                        .getResultList();
                return list.isEmpty() ? null : list.get(0);
            } catch (Exception e) {
                return null;
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Boolean> delete(Long id) {
        return supplyAsync(() -> wrap(em -> {
            try {
                PlaidItem item = em.find(PlaidItem.class, id);
                if (item != null) {
                    em.remove(item);
                    return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }
}
