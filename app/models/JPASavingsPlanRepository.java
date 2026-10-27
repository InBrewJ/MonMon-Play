package models;

import org.hibernate.annotations.QueryHints;
import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class JPASavingsPlanRepository implements SavingsPlanRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPASavingsPlanRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<SavingsPlan> add(SavingsPlan plan) {
        return supplyAsync(() -> wrap(em -> insert(em, plan)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> createOrReplace(String userId, SavingsPlan plan) {
        return supplyAsync(() -> wrap(em -> createOrReplace(em, userId, plan)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> getActive(String userId) {
        return supplyAsync(() -> wrap(em -> getActive(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> findById(Long id) {
        return supplyAsync(() -> wrap(em -> em.find(SavingsPlan.class, id)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> archive(Long planId) {
        return supplyAsync(() -> wrap(em -> archive(em, planId)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlanItem> addItem(Long planId, SavingsPlanItem item) {
        return supplyAsync(() -> wrap(em -> addItem(em, planId, item)), executionContext);
    }

    @Override
    public CompletionStage<Boolean> archiveItem(Long itemId) {
        return supplyAsync(() -> wrap(em -> archiveItem(em, itemId)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> updatePercentage(Long planId, int percent) {
        return supplyAsync(() -> wrap(em -> updatePercentage(em, planId, percent)), executionContext);
    }

    @Override
    public CompletionStage<Stream<SavingsPlan>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<SavingsPlan>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private SavingsPlan insert(EntityManager em, SavingsPlan plan) {
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

    private SavingsPlan createOrReplace(EntityManager em, String userId, SavingsPlan plan) {
        // Enforce strictly one savings plan active at once by soft-deleting any active plans
        List<SavingsPlan> activePlans = em.createQuery(
                "select sp from SavingsPlan sp where sp.userId = :userId and sp.archived = false",
                SavingsPlan.class)
                .setParameter("userId", userId)
                .getResultList();

        long now = System.currentTimeMillis();
        for (SavingsPlan existing : activePlans) {
            existing.setArchived(true);
            existing.setLastUpdated(now);
            em.merge(existing);
        }

        plan.setUserId(userId);
        plan.setArchived(false);
        plan.setCreatedAt(now);
        plan.setLastUpdated(now);
        em.persist(plan);
        return plan;
    }

    private SavingsPlan getActive(EntityManager em, String userId) {
        try {
            List<SavingsPlan> active = em.createQuery(
                    "select distinct sp from SavingsPlan sp left join fetch sp.items where sp.userId = :userId and sp.archived = false order by sp.createdAt desc",
                    SavingsPlan.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return active.isEmpty() ? null : active.get(0);
        } catch (Exception e) {
            System.err.println("Error fetching active savings plan: " + e.getMessage());
            return null;
        }
    }

    private SavingsPlan archive(EntityManager em, Long planId) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan != null) {
            plan.setArchived(true);
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
        }
        return plan;
    }

    private SavingsPlanItem addItem(EntityManager em, Long planId, SavingsPlanItem item) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan == null) {
            throw new IllegalArgumentException("Savings plan not found with id: " + planId);
        }
        item.setSavingsPlan(plan);
        item.setArchived(false);
        plan.addItem(item);
        plan.setLastUpdated(System.currentTimeMillis());
        em.persist(item);
        em.merge(plan);
        return item;
    }

    private Boolean archiveItem(EntityManager em, Long itemId) {
        SavingsPlanItem item = em.find(SavingsPlanItem.class, itemId);
        if (item != null) {
            item.setArchived(true);
            if (item.getSavingsPlan() != null) {
                item.getSavingsPlan().setLastUpdated(System.currentTimeMillis());
                em.merge(item.getSavingsPlan());
            }
            em.merge(item);
            return true;
        }
        return false;
    }

    private SavingsPlan updatePercentage(EntityManager em, Long planId, int percent) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan != null) {
            plan.setSavingsPercentage(percent);
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
        }
        return plan;
    }

    private Stream<SavingsPlan> list(EntityManager em, String userId) {
        try {
            List<SavingsPlan> plans = em.createQuery(
                    "select distinct sp from SavingsPlan sp left join fetch sp.items where sp.userId = :userId and sp.archived = false order by sp.createdAt desc",
                    SavingsPlan.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return plans.stream();
        } catch (Exception e) {
            return Collections.<SavingsPlan>emptyList().stream();
        }
    }

    private Stream<SavingsPlan> listComplete(EntityManager em, String userId) {
        try {
            List<SavingsPlan> plans = em.createQuery(
                    "select distinct sp from SavingsPlan sp left join fetch sp.items where sp.userId = :userId order by sp.createdAt desc",
                    SavingsPlan.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return plans.stream();
        } catch (Exception e) {
            return Collections.<SavingsPlan>emptyList().stream();
        }
    }
}
