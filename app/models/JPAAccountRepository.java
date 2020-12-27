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
public class JPAAccountRepository implements AccountRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAAccountRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Account> add(Account account) {
        return supplyAsync(() -> wrap(em -> insert(em, account)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Account>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Account insert(EntityManager em, Account account) {
        em.persist(account);
        return account;
    }

    private Stream<Account> list(EntityManager em) {
        List<Account> accounts = em.createQuery("select a from Account a ORDER BY a.type DESC", Account.class).getResultList();
        return accounts.stream();
    }
}
