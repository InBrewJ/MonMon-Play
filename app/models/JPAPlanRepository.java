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
    public CompletionStage<Stream<Plan>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    @Override
    public CompletionStage<Plan> archive(int planId) {
        return supplyAsync(() -> wrap(em -> archive(em, planId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Plan>> listComplete() {
        return supplyAsync(() -> wrap(em -> listComplete(em)), executionContext);
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

    private Stream<Plan> listComplete(EntityManager em) {
        List<Plan> plans = em.createQuery("select p from Plan p", Plan.class).getResultList();
        return plans.stream();
    }

    private Stream<Plan> list(EntityManager em) {
        List<Plan> plans = em.createQuery("select p from Plan p WHERE p.archived = false", Plan.class).getResultList();
        return plans.stream();
    }
}
