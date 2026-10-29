package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class JPAMealPlanRepository implements MealPlanRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private volatile boolean tableInitialized = false;

    @Inject
    public JPAMealPlanRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    private void ensureTableExists(EntityManager em) {
        if (!tableInitialized) {
            synchronized (this) {
                if (!tableInitialized) {
                    try {
                        em.createNativeQuery(
                                "CREATE TABLE IF NOT EXISTS locked_meal_plan (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "userid VARCHAR(255) NOT NULL, " +
                                        "planjson TEXT NOT NULL, " +
                                        "weeklybudget REAL, " +
                                        "people INTEGER, " +
                                        "postcode VARCHAR(32), " +
                                        "lockedat BIGINT" +
                                        ")"
                        ).executeUpdate();
                        try {
                            em.createNativeQuery("CREATE UNIQUE INDEX IF NOT EXISTS idx_locked_meal_plan_user ON locked_meal_plan(userid)").executeUpdate();
                        } catch (Exception ignored) {}
                    } catch (Exception ignored) {}
                    tableInitialized = true;
                }
            }
        }
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(em -> {
            ensureTableExists(em);
            return function.apply(em);
        });
    }

    @Override
    public CompletionStage<LockedMealPlan> saveOrUpdate(LockedMealPlan plan) {
        return supplyAsync(() -> wrap(em -> {
            List<LockedMealPlan> existing = em.createQuery(
                    "SELECT p FROM LockedMealPlan p WHERE p.userId = :userId", LockedMealPlan.class)
                    .setParameter("userId", plan.getUserId())
                    .getResultList();
            if (!existing.isEmpty()) {
                LockedMealPlan prev = existing.get(0);
                prev.setPlanJson(plan.getPlanJson());
                prev.setWeeklyBudget(plan.getWeeklyBudget());
                prev.setPeople(plan.getPeople());
                prev.setPostcode(plan.getPostcode());
                prev.setLockedAt(plan.getLockedAt());
                return em.merge(prev);
            } else {
                em.persist(plan);
                return plan;
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<LockedMealPlan>> findByUserId(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<LockedMealPlan> list = em.createQuery(
                        "SELECT p FROM LockedMealPlan p WHERE p.userId = :userId", LockedMealPlan.class)
                        .setParameter("userId", userId)
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
                int deleted = em.createQuery("DELETE FROM LockedMealPlan p WHERE p.userId = :userId")
                        .setParameter("userId", userId)
                        .executeUpdate();
                return deleted > 0;
            } catch (Exception e) {
                return false;
            }
        }), executionContext);
    }
}
