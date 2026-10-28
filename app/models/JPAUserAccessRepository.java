package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@Singleton
public class JPAUserAccessRepository implements UserAccessRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAUserAccessRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    @Override
    public CompletionStage<UserAccess> getOrCreate(String userId, String username, String email) {
        return supplyAsync(() -> wrap(em -> getOrCreateInternal(em, userId, username, email)), executionContext);
    }

    @Override
    public UserAccess getOrCreateSync(String userId, String username, String email) {
        return wrap(em -> getOrCreateInternal(em, userId, username, email));
    }

    private UserAccess getOrCreateInternal(EntityManager em, String userId, String username, String email) {
        try {
            UserAccess existing = em.createQuery(
                    "SELECT u FROM UserAccess u WHERE u.userId = :userId", UserAccess.class)
                    .setParameter("userId", userId)
                    .getSingleResult();

            boolean updated = false;
            if (username != null && !username.trim().isEmpty() && !username.equals(existing.getUsername())) {
                existing.setUsername(username);
                updated = true;
            }
            if (email != null && !email.trim().isEmpty() && !email.equals(existing.getEmail())) {
                existing.setEmail(email);
                updated = true;
            }
            if (updated) {
                em.merge(existing);
            }
            return existing;
        } catch (NoResultException e) {
            // New user registration. Determine whether to auto-approve:
            // 1) Does this user already have existing accounts in the database?
            Long existingAccountCount = 0L;
            try {
                existingAccountCount = em.createQuery(
                        "SELECT count(a) FROM Account a WHERE a.userId = :userId", Long.class)
                        .setParameter("userId", userId)
                        .getSingleResult();
            } catch (Exception ex) {
                existingAccountCount = 0L;
            }

            // 2) Are there any approved users currently in the system?
            Long approvedCount = 0L;
            try {
                approvedCount = em.createQuery(
                        "SELECT count(u) FROM UserAccess u WHERE u.status = :status", Long.class)
                        .setParameter("status", UserAccess.AccessStatus.APPROVED)
                        .getSingleResult();
            } catch (Exception ex) {
                approvedCount = 0L;
            }

            UserAccess newUser = new UserAccess();
            newUser.setUserId(userId);
            newUser.setUsername(username != null && !username.trim().isEmpty() ? username : "User");
            newUser.setEmail(email != null ? email : "");
            newUser.setCreatedAt(Instant.now());

            if (existingAccountCount > 0 || approvedCount == 0) {
                // Grandfather existing user who already has accounts, or auto-approve the first system user
                newUser.setStatus(UserAccess.AccessStatus.APPROVED);
                newUser.setApprovedAt(Instant.now());
                newUser.setApprovedBy("INITIAL_ADMIN");
            } else {
                newUser.setStatus(UserAccess.AccessStatus.PENDING);
            }

            em.persist(newUser);
            return newUser;
        }
    }

    @Override
    public CompletionStage<Optional<UserAccess>> findByUserId(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                UserAccess u = em.createQuery("SELECT u FROM UserAccess u WHERE u.userId = :userId", UserAccess.class)
                        .setParameter("userId", userId)
                        .getSingleResult();
                return Optional.of(u);
            } catch (NoResultException e) {
                return Optional.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<UserAccess>> findById(Long id) {
        return supplyAsync(() -> wrap(em -> Optional.ofNullable(em.find(UserAccess.class, id))), executionContext);
    }

    @Override
    public CompletionStage<List<UserAccess>> listPending() {
        return supplyAsync(() -> wrap(em ->
                em.createQuery("SELECT u FROM UserAccess u WHERE u.status = :status ORDER BY u.createdAt ASC", UserAccess.class)
                        .setParameter("status", UserAccess.AccessStatus.PENDING)
                        .getResultList()
        ), executionContext);
    }

    @Override
    public CompletionStage<List<UserAccess>> listApproved() {
        return supplyAsync(() -> wrap(em ->
                em.createQuery("SELECT u FROM UserAccess u WHERE u.status = :status ORDER BY u.approvedAt DESC", UserAccess.class)
                        .setParameter("status", UserAccess.AccessStatus.APPROVED)
                        .getResultList()
        ), executionContext);
    }

    @Override
    public CompletionStage<List<UserAccess>> listAll() {
        return supplyAsync(() -> wrap(em ->
                em.createQuery("SELECT u FROM UserAccess u ORDER BY u.createdAt DESC", UserAccess.class)
                        .getResultList()
        ), executionContext);
    }

    @Override
    public CompletionStage<UserAccess> approve(Long id, String approvedBy) {
        return supplyAsync(() -> wrap(em -> {
            UserAccess u = em.find(UserAccess.class, id);
            if (u != null) {
                u.setStatus(UserAccess.AccessStatus.APPROVED);
                u.setApprovedAt(Instant.now());
                u.setApprovedBy(approvedBy != null ? approvedBy : "admin");
                em.merge(u);
            }
            return u;
        }), executionContext);
    }

    @Override
    public CompletionStage<UserAccess> reject(Long id, String rejectedBy) {
        return supplyAsync(() -> wrap(em -> {
            UserAccess u = em.find(UserAccess.class, id);
            if (u != null) {
                u.setStatus(UserAccess.AccessStatus.REJECTED);
                u.setApprovedBy(rejectedBy != null ? rejectedBy : "admin");
                em.merge(u);
            }
            return u;
        }), executionContext);
    }

    @Override
    public CompletionStage<Boolean> isUserApproved(String userId) {
        return supplyAsync(() -> wrap(em -> isUserApprovedInternal(em, userId)), executionContext);
    }

    @Override
    public boolean isUserApprovedSync(String userId) {
        return wrap(em -> isUserApprovedInternal(em, userId));
    }

    private boolean isUserApprovedInternal(EntityManager em, String userId) {
        try {
            UserAccess u = em.createQuery("SELECT u FROM UserAccess u WHERE u.userId = :userId", UserAccess.class)
                    .setParameter("userId", userId)
                    .getSingleResult();
            return u.isApproved();
        } catch (NoResultException e) {
            return false;
        }
    }
}
