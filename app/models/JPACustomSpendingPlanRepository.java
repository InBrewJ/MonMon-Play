package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class JPACustomSpendingPlanRepository implements CustomSpendingPlanRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private static volatile boolean schemaEnsured = false;

    @Inject
    public JPACustomSpendingPlanRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema(jpaApi);
    }

    private static void ensureSchema(JPAApi jpaApi) {
        if (!schemaEnsured) {
            synchronized (JPACustomSpendingPlanRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS custom_spending_plan (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "user_id VARCHAR(255), " +
                                        "name VARCHAR(255), " +
                                        "sections_json TEXT, " +
                                        "archived BOOLEAN DEFAULT FALSE, " +
                                        "created_at BIGINT, " +
                                        "last_updated BIGINT" +
                                        ")",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS sections_json TEXT",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS created_at BIGINT",
                                "ALTER TABLE custom_spending_plan ADD COLUMN IF NOT EXISTS last_updated BIGINT",
                                "ALTER TABLE pot ADD COLUMN IF NOT EXISTS spending_plan_sections_json TEXT"
                        };
                        for (String sql : statements) {
                            try {
                                jpaApi.withTransaction((java.util.function.Consumer<EntityManager>) em -> {
                                    em.createNativeQuery(sql).executeUpdate();
                                });
                            } catch (Exception ignored) {}
                        }
                        schemaEnsured = true;
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public CompletionStage<CustomSpendingPlan> add(CustomSpendingPlan plan) {
        return supplyAsync(() -> wrap(em -> insert(em, plan)), executionContext);
    }

    @Override
    public CompletionStage<CustomSpendingPlan> findById(Long id) {
        return supplyAsync(() -> wrap(em -> em.find(CustomSpendingPlan.class, id)), executionContext);
    }

    @Override
    public CompletionStage<CustomSpendingPlan> createOrUpdate(String userId, String name, List<SpendingPlanSection> sections) {
        return supplyAsync(() -> wrap(em -> createOrUpdate(em, userId, name, sections)), executionContext);
    }

    @Override
    public CompletionStage<Stream<CustomSpendingPlan>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Boolean> archive(Long id) {
        return supplyAsync(() -> wrap(em -> archive(em, id)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private CustomSpendingPlan insert(EntityManager em, CustomSpendingPlan plan) {
        long now = System.currentTimeMillis();
        if (plan.getCreatedAt() == null) {
            plan.setCreatedAt(now);
        }
        plan.setLastUpdated(now);
        if (plan.getId() == null) {
            em.persist(plan);
            return plan;
        } else {
            return em.merge(plan);
        }
    }

    private CustomSpendingPlan createOrUpdate(EntityManager em, String userId, String name, List<SpendingPlanSection> sections) {
        String cleanName = name != null && !name.trim().isEmpty() ? name.trim() : "Custom Plan";
        List<CustomSpendingPlan> existing = em.createQuery(
                "select p from CustomSpendingPlan p where p.userId = :userId and lower(p.name) = :name and p.archived = false",
                CustomSpendingPlan.class)
                .setParameter("userId", userId)
                .setParameter("name", cleanName.toLowerCase())
                .getResultList();

        long now = System.currentTimeMillis();
        if (!existing.isEmpty()) {
            CustomSpendingPlan plan = existing.get(0);
            plan.setName(cleanName);
            plan.setSections(sections);
            plan.setLastUpdated(now);
            return em.merge(plan);
        } else {
            CustomSpendingPlan plan = new CustomSpendingPlan(userId, cleanName, sections);
            plan.setCreatedAt(now);
            plan.setLastUpdated(now);
            em.persist(plan);
            return plan;
        }
    }

    private Stream<CustomSpendingPlan> list(EntityManager em, String userId) {
        try {
            List<CustomSpendingPlan> plans = em.createQuery(
                    "select p from CustomSpendingPlan p where p.userId = :userId and p.archived = false order by p.name asc",
                    CustomSpendingPlan.class)
                    .setParameter("userId", userId)
                    .getResultList();
            return plans.stream();
        } catch (Exception e) {
            return Collections.<CustomSpendingPlan>emptyList().stream();
        }
    }

    private Boolean archive(EntityManager em, Long id) {
        CustomSpendingPlan plan = em.find(CustomSpendingPlan.class, id);
        if (plan != null) {
            plan.setArchived(true);
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
            return true;
        }
        return false;
    }
}
