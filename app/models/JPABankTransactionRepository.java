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
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS merchant_emoji VARCHAR(50)",
                                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS raw_data TEXT",
                                "ALTER TABLE bank_transaction ALTER COLUMN raw_data TYPE TEXT USING raw_data::text"
                        };
                        for (String sql : statements) {
                            try {
                                jpaApi.withTransaction(em -> {
                                    em.createNativeQuery(sql).executeUpdate();
                                });
                            } catch (Exception ignored) {
                            }
                        }
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
                        if (tx.getMerchantAddress() != null) prev.setMerchantAddress(tx.getMerchantAddress());
                        if (tx.getLatitude() != null) prev.setLatitude(tx.getLatitude());
                        if (tx.getLongitude() != null) prev.setLongitude(tx.getLongitude());
                        if (tx.getMerchantEmoji() != null) prev.setMerchantEmoji(tx.getMerchantEmoji());
                        if (tx.getRawData() != null) prev.setRawData(tx.getRawData());
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

    @Override
    public CompletionStage<List<BankTransaction>> findGeotaggedTransactions(String userId, int limit) {
        return supplyAsync(() -> wrap(em -> {
            try {
                // First try strictly MONZO transactions with coordinates
                List<BankTransaction> list = em.createQuery(
                        "select t from BankTransaction t where t.userId = :userId " +
                                "and t.latitude is not null and t.longitude is not null and t.amount > 0 " +
                                "and (t.source = 'MONZO' or t.accountId in (select a.id from Account a where a.userId = :userId and lower(a.name) like '%monzo%')) " +
                                "order by t.timestamp desc",
                        BankTransaction.class)
                        .setParameter("userId", userId)
                        .setMaxResults(limit > 0 ? limit : 2000)
                        .getResultList();

                // If no strictly Monzo transactions found, check if user has other geotagged transactions
                if (list.isEmpty()) {
                    list = em.createQuery(
                            "select t from BankTransaction t where t.userId = :userId " +
                                    "and t.latitude is not null and t.longitude is not null and t.amount > 0 " +
                                    "order by t.timestamp desc",
                            BankTransaction.class)
                            .setParameter("userId", userId)
                            .setMaxResults(limit > 0 ? limit : 2000)
                            .getResultList();
                }
                return list;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }), executionContext);
    }

    @Override
    public CompletionStage<List<viewModels.CategorySpend>> getCategorySpendingHistogram(String userId) {
        return supplyAsync(() -> wrap(em -> {
            List<viewModels.CategorySpend> results = new ArrayList<>();
            try {
                // Heavy aggregation preferred in PostgreSQL: native window function for percentage and totals
                String sql = "SELECT COALESCE(NULLIF(TRIM(t.category), ''), 'General') as cat, " +
                        "COUNT(*) as cnt, " +
                        "CAST(COALESCE(SUM(t.amount), 0.0) AS DOUBLE PRECISION) as total_spent, " +
                        "CAST(COALESCE(AVG(t.amount), 0.0) AS DOUBLE PRECISION) as avg_spent, " +
                        "CAST(ROUND((COALESCE(SUM(t.amount), 0.0) / NULLIF(SUM(SUM(t.amount)) OVER (), 0) * 100)::numeric, 1) AS DOUBLE PRECISION) as pct " +
                        "FROM bank_transaction t " +
                        "WHERE t.user_id = :userId AND t.amount > 0 " +
                        "AND (t.source = 'MONZO' OR t.account_id IN (SELECT a.id FROM accounts a WHERE a.user_id = :userId AND LOWER(a.name) LIKE '%monzo%')) " +
                        "GROUP BY COALESCE(NULLIF(TRIM(t.category), ''), 'General') " +
                        "ORDER BY total_spent DESC";

                List<Object[]> rows = em.createNativeQuery(sql)
                        .setParameter("userId", userId)
                        .getResultList();

                if (rows.isEmpty()) {
                    String fallbackSql = "SELECT COALESCE(NULLIF(TRIM(t.category), ''), 'General') as cat, " +
                            "COUNT(*) as cnt, " +
                            "CAST(COALESCE(SUM(t.amount), 0.0) AS DOUBLE PRECISION) as total_spent, " +
                            "CAST(COALESCE(AVG(t.amount), 0.0) AS DOUBLE PRECISION) as avg_spent, " +
                            "CAST(ROUND((COALESCE(SUM(t.amount), 0.0) / NULLIF(SUM(SUM(t.amount)) OVER (), 0) * 100)::numeric, 1) AS DOUBLE PRECISION) as pct " +
                            "FROM bank_transaction t " +
                            "WHERE t.user_id = :userId AND t.amount > 0 " +
                            "GROUP BY COALESCE(NULLIF(TRIM(t.category), ''), 'General') " +
                            "ORDER BY total_spent DESC";
                    rows = em.createNativeQuery(fallbackSql)
                            .setParameter("userId", userId)
                            .getResultList();
                }

                for (Object[] row : rows) {
                    String cat = row[0] != null ? row[0].toString() : "General";
                    long cnt = row[1] != null ? ((Number) row[1]).longValue() : 0L;
                    double total = row[2] != null ? ((Number) row[2]).doubleValue() : 0.0;
                    double avg = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
                    double pct = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;
                    results.add(new viewModels.CategorySpend(cat, cnt, total, avg, pct));
                }
            } catch (Exception e) {
                try {
                    List<Object[]> jpqlRows = em.createQuery(
                            "select coalesce(nullif(trim(t.category), ''), 'General'), count(t), coalesce(sum(t.amount), 0.0), coalesce(avg(t.amount), 0.0) " +
                                    "from BankTransaction t where t.userId = :userId and t.amount > 0 " +
                                    "group by coalesce(nullif(trim(t.category), ''), 'General') " +
                                    "order by coalesce(sum(t.amount), 0.0) desc")
                            .setParameter("userId", userId)
                            .getResultList();
                    double grandTotal = 0.0;
                    for (Object[] r : jpqlRows) {
                        grandTotal += r[2] != null ? ((Number) r[2]).doubleValue() : 0.0;
                    }
                    for (Object[] r : jpqlRows) {
                        String cat = r[0] != null ? r[0].toString() : "General";
                        long cnt = r[1] != null ? ((Number) r[1]).longValue() : 0L;
                        double total = r[2] != null ? ((Number) r[2]).doubleValue() : 0.0;
                        double avg = r[3] != null ? ((Number) r[3]).doubleValue() : 0.0;
                        double pct = grandTotal > 0 ? (Math.round((total / grandTotal * 100.0) * 10.0) / 10.0) : 0.0;
                        results.add(new viewModels.CategorySpend(cat, cnt, total, avg, pct));
                    }
                } catch (Exception ignored) {
                }
            }
            return results;
        }), executionContext);
    }

    private <T> T wrap(Function<EntityManager, T> function) {
        return jpaApi.withTransaction(function);
    }
}
