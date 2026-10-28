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
import static helpers.MathHelpers.round2;

public class JPASavingsPlanRepository implements SavingsPlanRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private static volatile boolean schemaEnsured = false;

    @Inject
    public JPASavingsPlanRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema(jpaApi);
    }

    private static void ensureSchema(JPAApi jpaApi) {
        if (!schemaEnsured) {
            synchronized (JPASavingsPlanRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS savings_plan (id BIGSERIAL PRIMARY KEY, user_id VARCHAR(255), savings_percentage REAL DEFAULT 0, archived BOOLEAN DEFAULT FALSE, last_updated BIGINT)",
                                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)",
                                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS savings_percentage REAL DEFAULT 0",
                                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS last_updated BIGINT",
                                "CREATE TABLE IF NOT EXISTS savings_plan_item (id BIGSERIAL PRIMARY KEY, savings_plan_id BIGINT, name VARCHAR(255), type VARCHAR(50), account_id BIGINT, target_amount REAL DEFAULT 0, monthly_amount REAL DEFAULT 0, priority INTEGER DEFAULT 1, locked BOOLEAN DEFAULT FALSE, archived BOOLEAN DEFAULT FALSE)",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS priority INTEGER DEFAULT 1",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS locked BOOLEAN DEFAULT FALSE",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS target_amount REAL DEFAULT 0",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS monthly_amount REAL DEFAULT 0",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS account_id BIGINT",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS type VARCHAR(50)",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS savings_plan_id BIGINT"
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
    public CompletionStage<SavingsPlanItem> findItemById(Long itemId) {
        return supplyAsync(() -> wrap(em -> findItemById(em, itemId)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlanItem> toggleLock(Long itemId) {
        return supplyAsync(() -> wrap(em -> toggleLock(em, itemId)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> balanceEvenly(Long planId, Float incomingTotal) {
        return supplyAsync(() -> wrap(em -> balanceEvenly(em, planId, incomingTotal)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> updateItemAmounts(Long planId, java.util.Map<Long, Float> amounts) {
        return supplyAsync(() -> wrap(em -> updateItemAmounts(em, planId, amounts)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlanItem> updateItem(Long itemId, String name, SavingsPlanItem.ItemType type, Long accountId, Float targetAmount, Float monthlyAmount, Integer priority) {
        return supplyAsync(() -> wrap(em -> updateItem(em, itemId, name, type, accountId, targetAmount, monthlyAmount, priority)), executionContext);
    }

    @Override
    public CompletionStage<Boolean> archiveItem(Long itemId) {
        return supplyAsync(() -> wrap(em -> archiveItem(em, itemId)), executionContext);
    }

    @Override
    public CompletionStage<Boolean> moveItemUp(Long itemId) {
        return supplyAsync(() -> wrap(em -> moveItem(em, itemId, true)), executionContext);
    }

    @Override
    public CompletionStage<Boolean> moveItemDown(Long itemId) {
        return supplyAsync(() -> wrap(em -> moveItem(em, itemId, false)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> updatePercentage(Long planId, int percent) {
        return supplyAsync(() -> wrap(em -> updatePercentage(em, planId, percent)), executionContext);
    }

    @Override
    public CompletionStage<SavingsPlan> updatePercentageAndScaleItems(Long planId, int percent, Float incomingTotal) {
        return supplyAsync(() -> wrap(em -> updatePercentageAndScaleItems(em, planId, percent, incomingTotal)), executionContext);
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

    private void selfHealPlanPriorities(EntityManager em, SavingsPlan plan) {
        if (plan == null || plan.isArchived()) return;
        List<SavingsPlanItem> items = plan.getItems();
        boolean needsRebalance = false;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).getPriority() == null || items.get(i).getPriority() != (i + 1)) {
                needsRebalance = true;
                break;
            }
        }
        if (needsRebalance) {
            plan.rebalancePriorities();
            for (SavingsPlanItem itm : items) {
                em.merge(itm);
            }
            em.merge(plan);
        }
    }

    private SavingsPlan getActive(EntityManager em, String userId) {
        try {
            List<SavingsPlan> active = em.createQuery(
                    "select distinct sp from SavingsPlan sp left join fetch sp.items where sp.userId = :userId and sp.archived = false order by sp.createdAt desc",
                    SavingsPlan.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            if (active.isEmpty()) {
                return null;
            }
            SavingsPlan plan = active.get(0);
            selfHealPlanPriorities(em, plan);
            return plan;
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
        plan.rebalancePriorities();
        em.persist(item);
        for (SavingsPlanItem itm : plan.getItems()) {
            em.merge(itm);
        }
        plan.setLastUpdated(System.currentTimeMillis());
        em.merge(plan);
        return item;
    }

    private SavingsPlanItem findItemById(EntityManager em, Long itemId) {
        return em.find(SavingsPlanItem.class, itemId);
    }

    private SavingsPlanItem toggleLock(EntityManager em, Long itemId) {
        SavingsPlanItem item = em.find(SavingsPlanItem.class, itemId);
        if (item != null) {
            item.setLocked(!item.isLocked());
            if (item.getSavingsPlan() != null) {
                item.getSavingsPlan().setLastUpdated(System.currentTimeMillis());
                em.merge(item.getSavingsPlan());
            }
            em.merge(item);
        }
        return item;
    }

    private SavingsPlan balanceEvenly(EntityManager em, Long planId, Float incomingTotal) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan != null) {
            plan.balanceEvenly(incomingTotal);
            em.merge(plan);
        }
        return plan;
    }

    private SavingsPlan updateItemAmounts(EntityManager em, Long planId, java.util.Map<Long, Float> amounts) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan != null && amounts != null) {
            for (SavingsPlanItem item : plan.getItems()) {
                if (amounts.containsKey(item.getId())) {
                    item.setMonthlyAmount(round2(amounts.get(item.getId())));
                    em.merge(item);
                }
            }
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
        }
        return plan;
    }

    private SavingsPlanItem updateItem(EntityManager em, Long itemId, String name, SavingsPlanItem.ItemType type, Long accountId, Float targetAmount, Float monthlyAmount, Integer priority) {
        SavingsPlanItem item = em.find(SavingsPlanItem.class, itemId);
        if (item == null) {
            return null;
        }
        item.setName(name);
        item.setType(type);
        if (accountId != null && accountId > 0) {
            Account acc = em.find(Account.class, accountId);
            item.setAccount(acc);
        } else {
            item.setAccount(null);
        }
        item.setTargetAmount(targetAmount != null ? targetAmount : 0.0f);
        item.setMonthlyAmount(monthlyAmount != null ? monthlyAmount : 0.0f);
        SavingsPlan plan = item.getSavingsPlan();
        if (plan != null) {
            List<SavingsPlanItem> active = plan.getItems();
            active.removeIf(i -> i.getId().equals(itemId));
            int targetIdx = (priority != null && priority > 0) ? Math.min(priority - 1, active.size()) : active.size();
            active.add(targetIdx, item);
            for (int i = 0; i < active.size(); i++) {
                active.get(i).setPriority(i + 1);
                em.merge(active.get(i));
            }
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
        } else {
            if (priority != null && priority > 0) {
                item.setPriority(priority);
            }
            em.merge(item);
        }
        return item;
    }

    private Boolean archiveItem(EntityManager em, Long itemId) {
        SavingsPlanItem item = em.find(SavingsPlanItem.class, itemId);
        if (item != null) {
            SavingsPlan plan = item.getSavingsPlan();
            if (plan != null) {
                plan.removeItemAndRebalanceQueue(item);
                for (SavingsPlanItem itm : plan.getItems()) {
                    em.merge(itm);
                }
                em.merge(plan);
            } else {
                item.setArchived(true);
                item.setMonthlyAmount(0.0f);
            }
            em.merge(item);
            return true;
        }
        return false;
    }

    private Boolean moveItem(EntityManager em, Long itemId, boolean up) {
        SavingsPlanItem item = em.find(SavingsPlanItem.class, itemId);
        if (item == null || item.getSavingsPlan() == null) {
            return false;
        }
        SavingsPlan plan = item.getSavingsPlan();
        boolean moved = up ? plan.moveItemUp(itemId) : plan.moveItemDown(itemId);
        if (moved) {
            for (SavingsPlanItem itm : plan.getItems()) {
                em.merge(itm);
            }
            plan.setLastUpdated(System.currentTimeMillis());
            em.merge(plan);
        }
        return moved;
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

    private SavingsPlan updatePercentageAndScaleItems(EntityManager em, Long planId, int percent, Float incomingTotal) {
        SavingsPlan plan = em.find(SavingsPlan.class, planId);
        if (plan != null) {
            plan.scaleItemsProportionally(incomingTotal, percent);
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
            for (SavingsPlan p : plans) {
                selfHealPlanPriorities(em, p);
            }
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
            for (SavingsPlan p : plans) {
                selfHealPlanPriorities(em, p);
            }
            return plans.stream();
        } catch (Exception e) {
            return Collections.<SavingsPlan>emptyList().stream();
        }
    }
}
