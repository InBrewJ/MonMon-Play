package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

/**
 * Provide JPA operations running inside of a thread pool sized to the connection pool
 */
public class JPAOutgoingRepository implements OutgoingRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAOutgoingRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Outgoing> add(Outgoing outgoing) {
        return supplyAsync(() -> wrap(em -> insert(em, outgoing)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Outgoing insert(EntityManager em, Outgoing outgoing) {
        em.persist(outgoing);
        return outgoing;
    }

    private Stream<Outgoing> list(EntityManager em) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p ORDER BY OUTGOINGDAY", Outgoing.class).getResultList();
        return outgoings.stream();
    }
}
