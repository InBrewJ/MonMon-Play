package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.time.LocalDate;
import java.util.ArrayList;
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

    @Override
    public CompletionStage<Stream<Outgoing>> rents() {
        return supplyAsync(() -> wrap(em -> rents(em)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> alreadyPaid(LocalDate asOf, int paydayDay) {
        return supplyAsync(() -> wrap(em -> alreadyPaid(em, asOf, paydayDay)), executionContext);
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

    private Stream<Outgoing> rents(EntityManager em) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p WHERE RENT = true", Outgoing.class).getResultList();
        return outgoings.stream();
    }

    private Stream<Outgoing> alreadyPaid(EntityManager em, LocalDate asOf, int paydayDay) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p", Outgoing.class).getResultList();
        List<Outgoing> found = new ArrayList<>();
        int dayOfasOf = asOf.getDayOfMonth();

        // Naive idea:
        // count back to last payday
        // get starting date
        // count up until asOf
        // at each step, find outgoing O(n) with that day -> remove it from the list
        // return the reduced list

        LocalDate lastPaydayDate = findLastPaydayDate(asOf, paydayDay);
        LocalDate searchDate = lastPaydayDate;
        do {
            for (Outgoing o: outgoings) {
                if (o.getOutgoingDay() == searchDate.getDayOfMonth()) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        } while (searchDate.getDayOfMonth() != asOf.getDayOfMonth());
        outgoings.removeAll(found);
        return found.stream();
    }

    LocalDate findLastPaydayDate(LocalDate asOf, int payday) {
        LocalDate possiblePayDate = asOf;
        while(possiblePayDate.getDayOfMonth() != payday) {
            possiblePayDate = possiblePayDate.minusDays(1);
        }
        return possiblePayDate;
    }
}
