package models;

import org.hibernate.annotations.QueryHints;
import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.util.Collections;
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
    public CompletionStage<Account> update(int accountId, Account account) {
        return supplyAsync(() -> wrap(em -> update(em, accountId, account)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Account>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Account>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Account> archive(int accountId) {
        return supplyAsync(() -> wrap(em -> archive(em, accountId)), executionContext);
    }

    @Override
    public CompletionStage<Account> findById(int accountId) {
        return supplyAsync(() -> wrap(em -> findById(em, accountId)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Account insert(EntityManager em, Account account) {
        em.persist(account);
        return account;
    }

    private Account update(EntityManager em, int accountId, Account account) {
        Account toUpdate = em.find(Account.class, (long)accountId);
        toUpdate.setName(account.getName());
        toUpdate.setNickname(account.getNickname());
        toUpdate.setType(account.getType());
        em.persist(toUpdate);
        return toUpdate;
    }

    private Account findById(EntityManager em,  int accountId) {
        TypedQuery<Account> query = em.createQuery(
                "select a from Account a WHERE a.id = :id" , Account.class);
        return query.setParameter("id", (long)accountId).getSingleResult();
    }

    private Account archive(EntityManager em, int accountId) {
        Account toArchive = em.find(Account.class, (long)accountId);
        System.out.println("Archiving :: " + toArchive.getName());
        toArchive.setArchived(true);
        em.persist(toArchive);
        return toArchive;
    }

    private Stream<Account> list(EntityManager em, String userId) {
        // https://stackoverflow.com/questions/30088649/how-to-use-multiple-join-fetch-in-one-jpql-query
        // This is to solve all sorts of horrid double fetch and cartesian product problems
        // But it works! Woohoo!
        try {
            List<Account> accounts = em.createQuery(
                    "select distinct a from Account a left join fetch a.outgoings where a.archived = false and a.userId = :userId ORDER BY a.type DESC",
                    Account.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            accounts = em.createQuery(
                    "select distinct a from Account a left join fetch a.balances WHERE a in :accounts ORDER BY a.type DESC",
                    Account.class)
                    .setParameter("accounts", accounts)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return accounts.stream();
        } catch (Exception e) {
            System.out.println("Likely no accounts yet, returning the empty list");
            List<Account> noAccounts = Collections.emptyList();
            return noAccounts.stream();
        }
    }

    private Stream<Account> listComplete(EntityManager em, String userId) {
        // https://stackoverflow.com/questions/30088649/how-to-use-multiple-join-fetch-in-one-jpql-query
        // This is to solve all sorts of horrid double fetch and cartesian product problems
        // But it works! Woohoo!
        try {
            List<Account> accounts = em.createQuery(
                    "select distinct a from Account a left join fetch a.outgoings WHERE a.userId = :userId ORDER BY a.type DESC",
                    Account.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            accounts = em.createQuery(
                    "select distinct a from Account a left join fetch a.balances WHERE a in :accounts ORDER BY a.type DESC",
                    Account.class)
                    .setParameter("accounts", accounts)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return accounts.stream();
        } catch (Exception e) {
            System.out.println("Likely no accounts yet, returning the empty list");
            List<Account> noAccounts = Collections.emptyList();
            return noAccounts.stream();
        }
    }
}
