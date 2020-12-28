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
public class JPABalanceRepository implements BalanceRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPABalanceRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Balance> add(Balance balance) {
        return supplyAsync(() -> wrap(em -> insert(em, balance)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Balance>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Balance insert(EntityManager em, Balance balance) {
        em.persist(balance);
        return balance;
    }

    private Stream<Balance> list(EntityManager em) {
        List<Balance> balances = em.createQuery("select b from Balance b ORDER BY b.timestamp DESC", Balance.class).getResultList();
        return balances.stream();
    }
}
