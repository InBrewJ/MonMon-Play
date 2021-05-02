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

public class JPAPotRepository implements PotRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAPotRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    @Override
    public CompletionStage<Pot> add(Pot pot) {
        return supplyAsync(() -> wrap(em -> insert(em, pot)), executionContext);
    };

    @Override
    public CompletionStage<Stream<Pot>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Pot>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Pot> archive(int potId) {
        return supplyAsync(() -> wrap(em -> archive(em, potId)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Pot archive(EntityManager em, int potId) {
        Pot toArchive = em.find(Pot.class, (long)potId);
        toArchive.setArchived(true);
        em.persist(toArchive);
        return toArchive;
    }

    private Pot insert(EntityManager em, Pot pot) {
        // Not sure about this, must read up!
        // https://xebia.com/blog/jpa-implementation-patterns-saving-detached-entities/
        em.merge(pot);
        return pot;
    }

    private Stream<Pot> list(EntityManager em, String userId) {
        try {
            List<Pot> pots = em.createQuery(
                    "select distinct p from Pot p left join fetch p.accounts where p.userId = :userId and p.archived = false",
                    Pot.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return pots.stream();
        } catch (Exception e) {
            System.out.println(e);
            System.out.println("Likely no pots, returning the empty list");
            List<Pot> noPots = Collections.emptyList();
            return noPots.stream();
        }
    }

    private Stream<Pot> listComplete(EntityManager em, String userId) {
        try {
            List<Pot> pots = em.createQuery(
                    "select distinct p from Pot p left join fetch p.accounts where p.userId = :userId",
                    Pot.class)
                    .setParameter("userId", userId)
                    .setHint(QueryHints.PASS_DISTINCT_THROUGH, false)
                    .getResultList();
            return pots.stream();
        } catch (Exception e) {
            System.out.println(e);
            System.out.println("Likely no pots, returning the empty list");
            List<Pot> noPots = Collections.emptyList();
            return noPots.stream();
        }
    }

//    @Override
//    public CompletionStage<Pot> update(int potId, Pot pot) {
//
//    };
//
//    @Override
//    public CompletionStage<Pot> archive(int potId) {
//
//    };
//
//    // List _without_ archived (for displayed)
//    @Override
//    public CompletionStage<Stream<Pot>> list(String userId) {
//
//    };

}
