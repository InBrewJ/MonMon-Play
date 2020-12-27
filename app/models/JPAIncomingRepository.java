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
public class JPAIncomingRepository implements IncomingRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAIncomingRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Incoming> add(Incoming incoming) {
        return supplyAsync(() -> wrap(em -> insert(em, incoming)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Incoming>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Incoming insert(EntityManager em, Incoming incoming) {
        em.persist(incoming);
        return incoming;
    }

    private Stream<Incoming> list(EntityManager em) {
        List<Incoming> incomings = em.createQuery("select i from Incoming i", Incoming.class).getResultList();
        return incomings.stream();
    }
}
