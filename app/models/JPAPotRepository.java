package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
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
    public CompletionStage<Stream<Pot>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Pot insert(EntityManager em, Pot pot) {
        em.persist(pot);
        return pot;
    }

    private Stream<Pot> listComplete(EntityManager em, String userId) {
        List<Pot> pots = em
                .createQuery("select p from Pot p WHERE userId = :userId", Pot.class)
                .setParameter("userId", userId)
                .getResultList();
        return pots.stream();
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
