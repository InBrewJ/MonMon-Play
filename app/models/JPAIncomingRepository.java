package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
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
    public CompletionStage<Incoming> archive(int incomingId) {
        return supplyAsync(() -> wrap(em -> archive(em, incomingId)), executionContext);
    }

    @Override
    public CompletionStage<Incoming> findById(int incomingId) {
        return supplyAsync(() -> wrap(em -> findById(em, incomingId)), executionContext);
    }

    @Override
    public CompletionStage<Incoming> update(int incomingId, Incoming incoming) {
        return supplyAsync(() -> wrap(em -> update(em, incomingId, incoming)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Incoming>> list() {
        return supplyAsync(() -> wrap(em -> list(em)), executionContext);
    }

    @Override
    public CompletionStage<Stream<Incoming>> listComplete() {
        return supplyAsync(() -> wrap(em -> listComplete(em)), executionContext);
    }

    @Override
    public int getNextPayDay() {
        CompletableFuture<List<Incoming>> incomings = supplyAsync(() -> wrap(em -> em.createQuery("select i from Incoming i where PAYDAY = true and i.archived = false", Incoming.class).setMaxResults(1).getResultList()), executionContext);
        try {
            List<Incoming> first = incomings.get();
            int theDay = first.get(0).getIncomingMonthDay();
            System.out.println("Found: " + theDay + " as payday");
            return theDay;
        } catch (InterruptedException e) {
            System.out.println("Finding payday future was: InterruptedException");
        } catch (ExecutionException e) {
            System.out.println("Finding payday future was: ExecutionException");
        } catch (IndexOutOfBoundsException e) {
            System.out.println("Finding payday future was: IndexOutOfBoundsException, probably no results found");
        }
        return 1;
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    private Incoming insert(EntityManager em, Incoming incoming) {
        em.persist(incoming);
        return incoming;
    }

    private Incoming archive(EntityManager em, int incomingId) {
        Incoming toArchive = em.find(Incoming.class, (long)incomingId);
        System.out.println("Archiving :: " + toArchive.getName());
        toArchive.setArchived(true);
        em.persist(toArchive);
        return toArchive;
    }

    private Incoming update(EntityManager em, int incomingId, Incoming incoming) {
        Incoming toUpdate = em.find(Incoming.class, (long)incomingId);
        System.out.println("Updating :: " + toUpdate.getName());
        toUpdate.setNetValue(incoming.getNetValue());
        toUpdate.setName(incoming.getName());
        toUpdate.setPayDay(incoming.payDay);
        toUpdate.setIncomingMonthDay(incoming.getIncomingMonthDay());
        toUpdate.setType(incoming.getType());
        em.persist(toUpdate);
        return toUpdate;
    }

    private Incoming findById(EntityManager em,  int incomingId) {
        TypedQuery<Incoming> query = em.createQuery(
                "select i from Incoming i WHERE i.id = :id" , Incoming.class);
        Incoming incoming = query.setParameter("id", (long)incomingId).getSingleResult();
        return incoming;
    }

    private Stream<Incoming> list(EntityManager em) {
        List<Incoming> incomings = em.createQuery("select i from Incoming i WHERE i.archived = false", Incoming.class).getResultList();
        return incomings.stream();
    }

    private Stream<Incoming> listComplete(EntityManager em) {
        List<Incoming> incomings = em.createQuery("select i from Incoming i", Incoming.class).getResultList();
        return incomings.stream();
    }

}
