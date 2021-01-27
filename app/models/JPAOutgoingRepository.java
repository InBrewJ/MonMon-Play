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
    public CompletionStage<Stream<Outgoing>> bills() {
        return supplyAsync(() -> wrap(em -> bills(em)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> alreadyPaid(LocalDate asOf, int paydayDay) {
        return supplyAsync(() -> wrap(em -> alreadyPaid(em, asOf, paydayDay)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> yetToPay(LocalDate asOf, int paydayDay) {
        return supplyAsync(() -> wrap(em -> yetToPay(em, asOf, paydayDay)), executionContext);
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

    private Stream<Outgoing> bills(EntityManager em) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p WHERE BILL = true", Outgoing.class).getResultList();
        return outgoings.stream();
    }

    private Stream<Outgoing> yetToPay(EntityManager em, LocalDate asOf, int paydayDay) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p", Outgoing.class).getResultList();
        List<Outgoing> paid = this.findAlreadyPaid(outgoings, asOf, paydayDay);
        outgoings.removeAll(paid);
        return outgoings.stream();
    }

    private Stream<Outgoing> alreadyPaid(EntityManager em, LocalDate asOf, int paydayDay) {
        List<Outgoing> outgoings = em.createQuery("select p from Outgoing p", Outgoing.class).getResultList();
        List<Outgoing> paid = this.findAlreadyPaid(outgoings, asOf, paydayDay);
        return paid.stream();
    }

    private List<Outgoing> findAlreadyPaid(List<Outgoing> outgoings, LocalDate asOf, int paydayDay) {
        List<Outgoing> found = new ArrayList<>();
        LocalDate searchDate = findLastPaydayDate(asOf, paydayDay);
        do {
            for (Outgoing o: outgoings) {
                if (o.getOutgoingDay() == searchDate.getDayOfMonth()) {
                    found.add(o);
                }
            }
            searchDate = searchDate.plusDays(1);
        } while (searchDate.getDayOfMonth() != asOf.plusDays(1).getDayOfMonth());
        return found;
    }

    LocalDate findLastPaydayDate(LocalDate asOf, int payday) {
        LocalDate possiblePayDate = asOf;
        while(possiblePayDate.getDayOfMonth() != payday) {
            possiblePayDate = possiblePayDate.minusDays(1);
        }
        return possiblePayDate;
    }
}
