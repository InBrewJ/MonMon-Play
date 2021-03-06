package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

public class JPAPlanRepository implements PlanRepository {
    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAPlanRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Plan> add(Plan plan) {
        return supplyAsync(() -> wrap(em -> insert(em, plan)), executionContext);
    }

    @Override
    public CompletionStage<Plan> createOrReplace(String userId, Plan.PlanType type, Plan plan) {
        return supplyAsync(() -> wrap(em -> createOrReplace(em, userId, type, plan)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Plan>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Plan> archive(int planId) {
        return supplyAsync(() -> wrap(em -> archive(em, planId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Plan>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    private Plan archive(EntityManager em, int planId) {
        Plan toArchive = em.find(Plan.class, (long)planId);
        System.out.println("Archiving :: " + toArchive.getType());
        toArchive.setArchived(true);
        em.persist(toArchive);
        return toArchive;
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Plan insert(EntityManager em, Plan plan) {
        em.persist(plan);
        return plan;
    }

    private Plan createOrReplace(EntityManager em, String userId, Plan.PlanType type, Plan plan) {
        Stream<Plan> plansByType = findByType(em, userId, type);
        // If we find any plans, archive them
        // Presumes that we can only have one type of plan
        plansByType.forEach((Plan p) -> {
            archive(em, Math.toIntExact(p.getId()));
        });
        // Once archived, add the new plan
        insert(em, plan);
        return plan;
    }

    private Stream<Plan> findByType(EntityManager em, String userId, Plan.PlanType type) {
        List<Plan> plans = em
                .createQuery("select p from Plan p WHERE p.archived = false and userId = :userId and type = :type", Plan.class)
                .setParameter("userId", userId)
                .setParameter("type", type)
                .getResultList();
        return plans.stream();
    }

    private Stream<Plan> listComplete(EntityManager em, String userId) {
        List<Plan> plans = em
                .createQuery("select p from Plan p WHERE userId = :userId", Plan.class)
                .setParameter("userId", userId)
                .getResultList();
        return plans.stream();
    }

    private Stream<Plan> list(EntityManager em, String userId) {
        List<Plan> plans = em
                .createQuery("select p from Plan p WHERE archived = FALSE and userId = :userId", Plan.class)
                .setParameter("userId", userId)
                .getResultList();
        return plans.stream();
    }
}
