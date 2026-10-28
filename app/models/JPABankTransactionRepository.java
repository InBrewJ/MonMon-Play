package models;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.persistence.EntityManager;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.stream.Stream;

import static java.util.concurrent.CompletableFuture.supplyAsync;

@SuppressWarnings("unchecked")
public class JPABankTransactionRepository implements BankTransactionRepository {

    private final JPAApi jpaApi;
    private final DatabaseExecutionContext executionContext;
    private static volatile boolean schemaEnsured = false;

    @Inject
    public JPABankTransactionRepository(JPAApi jpaApi, DatabaseExecutionContext executionContext) {
        this.jpaApi = jpaApi;
        this.executionContext = executionContext;
        ensureSchema(jpaApi);
    }

    private static void ensureSchema(JPAApi jpaApi) {
        if (!schemaEnsured) {
            synchronized (JPABankTransactionRepository.class) {
                if (!schemaEnsured) {
                    try {
                        String[] statements = new String[]{
                                "CREATE TABLE IF NOT EXISTS bank_transaction (" +
                                        "id BIGSERIAL PRIMARY KEY, " +
                                        "user_id VARCHAR(255), " +
                                        "account_id BIGINT, " +
                                        "external_id VARCHAR(255), " +
                                        "timestamp BIGINT, " +
                                        "date VARCHAR(64), " +
                                        "amount REAL, " +
                                        "description VARCHAR(500), " +
                                        "merchant_name VARCHAR(255), " +
                                        "category VARCHAR(255), " +
                                        "source VARCHAR(64), " +
                                        "pending BOOLEAN DEFAULT FALSE, " +
                                        "created_at BIGINT" +
                                        ")",
                                "CREATE INDEX IF NOT EXISTS idx_bt_user ON bank_transaction (user_id)",
                                "CREATE INDEX IF NOT EXISTS idx_bt_account ON bank_transaction (account_id)",
                                "CREATE INDEX IF NOT EXISTS idx_bt_time ON bank_transaction (timestamp)",
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS merchant_address VARCHAR(500)",
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION",
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION",
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS merchant_emoji VARCHAR(50)"
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

    @Override
    public CompletionStage<BankTransaction> save(BankTransaction transaction) {
        return supplyAsync(() -> wrap(em -> {
            if (transaction.getId() == null) {
                em.persist(transaction);
                return transaction;
            } else {
                return em.merge(transaction);
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Integer> saveAll(List<BankTransaction> transactions) {
        return supplyAsync(() -> wrap(em -> {
            int count = 0;
            for (BankTransaction tx : transactions) {
                if (tx.getExternalId() != null && !tx.getExternalId().trim().isEmpty()) {
                    List<BankTransaction> existing = em.createQuery(
                            "select t from BankTransaction t where t.externalId = :extId", BankTransaction.class)
                            .setParameter("extId", tx.getExternalId())
                            .getResultList();
                    if (!existing.isEmpty()) {
                        BankTransaction prev = existing.get(0);
                        prev.setAmount(tx.getAmount());
                        prev.setDate(tx.getDate());
                        prev.setDescription(tx.getDescription());
                        prev.setMerchantName(tx.getMerchantName());
                        prev.setCategory(tx.getCategory());
                        prev.setPending(tx.isPending());
                        em.merge(prev);
                        count++;
                        continue;
                    }
                }
                em.persist(tx);
                count++;
            }
            return count;
        }), executionContext);
    }

    @Override
    public CompletionStage<Stream<BankTransaction>> listRecent(String userId, int limit) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<BankTransaction> list = em.createQuery(
                        "select t from BankTransaction t where t.userId = :userId order by t.timestamp desc",
                        BankTransaction.class)
                        .setParameter("userId", userId)
                        .setMaxResults(limit > 0 ? limit : 100)
                        .getResultList();
                return list.stream();
            } catch (Exception e) {
                return Collections.<BankTransaction>emptyList().stream();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Stream<BankTransaction>> findByAccount(Long accountId, Long startSec, Long endSec) {
        return supplyAsync(() -> wrap(em -> {
            try {
                List<BankTransaction> list = em.createQuery(
                        "select t from BankTransaction t where t.accountId = :accountId " +
                                "and t.timestamp >= :startSec and t.timestamp <= :endSec " +
                                "order by t.timestamp desc",
                        BankTransaction.class)
                        .setParameter("accountId", accountId)
                        .setParameter("startSec", startSec)
                        .setParameter("endSec", endSec)
                        .getResultList();
                return list.stream();
            } catch (Exception e) {
                return Collections.<BankTransaction>emptyList().stream();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Double> getAccountSpentSum(Long accountId, Long startSec, Long endSec) {
        return supplyAsync(() -> wrap(em -> {
            try {
                Object result = em.createQuery(
                        "select coalesce(sum(t.amount), 0.0) from BankTransaction t " +
                                "where t.accountId = :accountId and t.timestamp >= :startSec and t.timestamp <= :endSec and t.amount > 0")
                        .setParameter("accountId", accountId)
                        .setParameter("startSec", startSec)
                        .setParameter("endSec", endSec)
                        .getSingleResult();
                return ((Number) result).doubleValue();
            } catch (Exception e) {
                return 0.0;
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Map<Long, Double>> getAccountsSpentSumMap(String userId, Long startSec, Long endSec) {
        return supplyAsync(() -> wrap(em -> {
            Map<Long, Double> map = new HashMap<>();
            try {
                List<Object[]> rows = em.createQuery(
                        "select t.accountId, coalesce(sum(t.amount), 0.0) from BankTransaction t " +
                                "where t.userId = :userId and t.timestamp >= :startSec and t.timestamp <= :endSec and t.amount > 0 " +
                                "group by t.accountId")
                        .setParameter("userId", userId)
                        .setParameter("startSec", startSec)
                        .setParameter("endSec", endSec)
                        .getResultList();
                for (Object[] row : rows) {
                    if (row[0] != null) {
                        Long accId = ((Number) row[0]).longValue();
                        Double sum = ((Number) row[1]).doubleValue();
                        map.put(accId, sum);
                    }
                }
            } catch (Exception ignored) {
            }
            return map;
        }), executionContext);
    }

    @Override
    public CompletionStage<Integer> deleteForAccount(Long accountId) {
        return supplyAsync(() -> wrap(em -> {
            try {
                return em.createQuery("delete from BankTransaction t where t.accountId = :accountId")
                        .setParameter("accountId", accountId)
                        .executeUpdate();
            } catch (Exception e) {
                return 0;
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<Integer> deleteByTimeRange(Long accountId, Long startSec, Long endSec) {
        return supplyAsync(() -> wrap(em -> {
            try {
                return em.createQuery("delete from BankTransaction t where t.accountId = :accountId " +
                        "and t.timestamp >= :startSec and t.timestamp <= :endSec")
                        .setParameter("accountId", accountId)
                        .setParameter("startSec", startSec)
                        .setParameter("endSec", endSec)
                        .executeUpdate();
            } catch (Exception e) {
                return 0;
            }
        }), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }
}
