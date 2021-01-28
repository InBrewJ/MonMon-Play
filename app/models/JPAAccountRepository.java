package models;

import org.hibernate.annotations.QueryHints;
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
        // https://stackoverflow.com/questions/30088649/how-to-use-multiple-join-fetch-in-one-jpql-query
        // This is to solve all sorts of horrid double fetch and cartesian product problems
        // But it works! Woohoo!
        List<Account> accounts = em.createQuery(
                "select distinct a from Account a left join fetch a.outgoings ORDER BY a.type DESC",
                Account.class)
                .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                .getResultList();
        accounts = em.createQuery(
                "select distinct a from Account a left join fetch a.balances WHERE a in :accounts ORDER BY a.type DESC",
                Account.class)
                .setParameter("accounts", accounts)
                .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                .getResultList();
        return accounts.stream();
    }
}
