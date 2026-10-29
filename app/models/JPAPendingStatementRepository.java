package models;

import org.slf4j.LoggerFactory;
import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.persistence.EntityManager;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@Singleton
public class JPAPendingStatementRepository implements PendingStatementRepository {
    private static final org.slf4j.Logger logger = LoggerFactory.getLogger(JPAPendingStatementRepository.class);
    private static volatile boolean schemaEnsured = false;

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;

    @Inject
    public JPAPendingStatementRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema();
    }

    private void ensureSchema() {
        if (!schemaEnsured) {
            synchronized (JPAPendingStatementRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS pending_statement (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "user_id VARCHAR(255), " +
                                        "job_id VARCHAR(255), " +
                                        "bank_name VARCHAR(255), " +
                                        "start_date VARCHAR(64), " +
                                        "end_date VARCHAR(64), " +
                                        "start_timestamp BIGINT, " +
                                        "end_timestamp BIGINT, " +
                                        "opening_balance REAL, " +
                                        "closing_balance REAL, " +
                                        "transaction_count INT, " +
                                        "matched_account_id BIGINT, " +
                                        "status VARCHAR(32), " +
                                        "raw_payload TEXT, " +
                                        "error_message TEXT, " +
                                        "created_at BIGINT, " +
                                        "applied_at BIGINT" +
                                        ")",
                                "CREATE INDEX IF NOT EXISTS idx_ps_user ON pending_statement (user_id)",
                                "CREATE INDEX IF NOT EXISTS idx_ps_status ON pending_statement (status)",
                                "CREATE INDEX IF NOT EXISTS idx_ps_job ON pending_statement (job_id)"
                        };
                        jpaApi.withTransaction(em -> {
                            for (String sql : statements) {
                                try {
                                    em.createNativeQuery(sql).executeUpdate();
                                } catch (Exception ignored) {
                                }
                            }
                        });
                        schemaEnsured = true;
                    } catch (Exception e) {
                        schemaEnsured = true;
                    }
                }
            }
        }
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }

    @Override
    public CompletionStage<PendingStatement> save(PendingStatement statement) {
        return supplyAsync(() -> wrap(em -> {
            if (statement.getId() == null) {
                em.persist(statement);
                return statement;
            } else {
                return em.merge(statement);
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<PendingStatement>> findById(Long id) {
        return supplyAsync(() -> wrap(em -> {
            PendingStatement stmt = em.find(PendingStatement.class, id);
            return Optional.ofNullable(stmt);
        }), executionContext);
    }

    @Override
    public CompletionStage<Optional<PendingStatement>> findByJobId(String jobId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                PendingStatement stmt = em.createQuery(
                        "SELECT p FROM PendingStatement p WHERE p.jobId = :jobId ORDER BY p.id DESC", PendingStatement.class)
                        .setParameter("jobId", jobId)
                        .setMaxResults(1)
                        .getSingleResult();
                return Optional.ofNullable(stmt);
            } catch (Exception e) {
                return Optional.empty();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<List<PendingStatement>> listPending(String userId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                return em.createQuery(
                        "SELECT p FROM PendingStatement p WHERE p.userId = :userId AND p.status = 'PENDING' ORDER BY p.createdAt DESC",
                        PendingStatement.class)
                        .setParameter("userId", userId)
                        .getResultList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<List<PendingStatement>> listRecent(String userId, int limit) {
        return supplyAsync(() -> wrap(em -> {
            try {
                int effectiveLimit = limit > 0 ? limit : 20;
                return em.createQuery(
                        "SELECT p FROM PendingStatement p WHERE p.userId = :userId ORDER BY p.createdAt DESC",
                        PendingStatement.class)
                        .setParameter("userId", userId)
                        .setMaxResults(effectiveLimit)
                        .getResultList();
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Boolean> delete(Long id) {
        return supplyAsync(() -> wrap(em -> {
            PendingStatement stmt = em.find(PendingStatement.class, id);
            if (stmt != null) {
                em.remove(stmt);
                return true;
            }
            return false;
        }), executionContext);
    }
}
