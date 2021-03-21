package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static helpers.ModelHelpers.findAlreadyPaid;
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
    public CompletionStage<Stream<Outgoing>> list(String userId) {
        return supplyAsync(() -> wrap(em -> list(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> listComplete(String userId) {
        return supplyAsync(() -> wrap(em -> listComplete(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> rents(String userId) {
        return supplyAsync(() -> wrap(em -> rents(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> bills(String userId) {
        return supplyAsync(() -> wrap(em -> bills(em, userId)), executionContext);
    }

    @Override
    public CompletionStage<Outgoing> findById(int outgoingId) {
        return supplyAsync(() -> wrap(em -> findById(em, outgoingId)), executionContext);
    }

    @Override
    public CompletionStage<Outgoing> update(int outgoingId, Outgoing outgoing) {
        return supplyAsync(() -> wrap(em -> update(em, outgoingId, outgoing)), executionContext);
    }

    @Override
    public CompletionStage<Outgoing> archive(int outgoingId) {
        return supplyAsync(() -> wrap(em -> archive(em, outgoingId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> alreadyPaid(LocalDate asOf, int paydayDay, String userId) {
        return supplyAsync(() -> wrap(em -> alreadyPaid(em, asOf, paydayDay, userId)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Outgoing>> yetToPay(LocalDate asOf, int paydayDay, String userId) {
        return supplyAsync(() -> wrap(em -> yetToPay(em, asOf, paydayDay, userId)), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Outgoing insert(EntityManager em, Outgoing outgoing) {
        em.persist(outgoing);
        return outgoing;
    }

    private Stream<Outgoing> list(EntityManager em, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p  where archived = false and userId = :userId ORDER BY OUTGOINGDAY", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        return outgoings.stream();
    }

    private Stream<Outgoing> listComplete(EntityManager em, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p WHERE userId = :userId ORDER BY OUTGOINGDAY", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        return outgoings.stream();
    }

    private Outgoing findById(EntityManager em,  int outgoingId) {
        TypedQuery<Outgoing> query = em.createQuery(
                "select o from Outgoing o WHERE .id = :id" , Outgoing.class);
        return query.setParameter("id", (long)outgoingId).getSingleResult();
    }

    private Outgoing update(EntityManager em, int outgoingId, Outgoing outgoing) {
        Outgoing toUpdate = em.find(Outgoing.class, (long)outgoingId);
        toUpdate.setName(outgoing.getName());
        toUpdate.setHiddenFromTotal(outgoing.isHiddenFromTotal());
        toUpdate.setCost(outgoing.getCost());
        toUpdate.setBill(outgoing.isBill());
        toUpdate.setRent(outgoing.isRent());
        toUpdate.setOutgoingDay(outgoing.getOutgoingDay());
        toUpdate.setAccount(outgoing.getAccount());
        em.persist(toUpdate);
        return toUpdate;
    }

    private Outgoing archive(EntityManager em, int outgoingId) {
        Outgoing toArchive = em.find(Outgoing.class, (long)outgoingId);
        System.out.println("Archiving :: " + toArchive.getName());
        toArchive.setArchived(true);
        em.persist(toArchive);
        return toArchive;
    }

    private Stream<Outgoing> rents(EntityManager em, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p WHERE RENT = true and archived = false and userId = :userId", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        return outgoings.stream();
    }

    private Stream<Outgoing> bills(EntityManager em, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p WHERE BILL = true and archived = false and userId = :userId", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        return outgoings.stream();
    }

    private Stream<Outgoing> yetToPay(EntityManager em, LocalDate asOf, int paydayDay, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p where archived = false and userId = :userId", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        List<Outgoing> paid = findAlreadyPaid(outgoings, asOf, paydayDay);
        outgoings.removeAll(paid);
        return outgoings.stream();
    }

    private Stream<Outgoing> alreadyPaid(EntityManager em, LocalDate asOf, int paydayDay, String userId) {
        List<Outgoing> outgoings = em
                .createQuery("select p from Outgoing p where archived = false and userId = :userId", Outgoing.class)
                .setParameter("userId", userId)
                .getResultList();
        List<Outgoing> paid = findAlreadyPaid(outgoings, asOf, paydayDay);
        return paid.stream();
    }
}
