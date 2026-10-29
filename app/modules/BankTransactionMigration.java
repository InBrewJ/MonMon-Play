package modules;

import play.db.jpa.JPAApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Startup migration to ensure tables, indexes, and column types for BankTransaction exist.
 * Specifically converts raw_data from jsonb (or any prior type) to TEXT with USING raw_data::text,
 * running each DDL command in its own isolated transaction so that partial errors do not abort subsequent statements.
 */
@Singleton
public class BankTransactionMigration {

    private static final Logger logger = LoggerFactory.getLogger(BankTransactionMigration.class);

    @Inject
    public BankTransactionMigration(JPAApi jpaApi) {
        logger.info("[BankTransactionMigration] Starting BankTransaction schema migration...");

        // 1. Ensure table exists
        runSingleStatement(jpaApi,
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
                        ")"
        );

        // 2. Ensure indexes
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_user ON bank_transaction (user_id)");
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_account ON bank_transaction (account_id)");
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_time ON bank_transaction (timestamp)");
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_extid ON bank_transaction (external_id)");

        // 3. Ensure columns exist
        String[] columns = new String[]{
                "merchant_address VARCHAR(500)",
                "latitude DOUBLE PRECISION",
                "longitude DOUBLE PRECISION",
                "merchant_emoji VARCHAR(50)",
                "raw_data TEXT"
        };
        for (String colDef : columns) {
            runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS " + colDef);
        }

        // 4. Critically: Ensure raw_data is of type TEXT in case it was created as jsonb or varchar
        // In PostgreSQL: ALTER TABLE bank_transaction ALTER COLUMN raw_data TYPE TEXT USING raw_data::text
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ALTER COLUMN raw_data TYPE TEXT USING raw_data::text");

        logger.info("[BankTransactionMigration] BankTransaction schema migration completed.");
    }

    private void runSingleStatement(JPAApi jpaApi, String sql) {
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(sql).executeUpdate();
            });
            logger.debug("[BankTransactionMigration] Executed: {}", sql);
        } catch (Exception e) {
            // Some statements (like H2 or SQLite dialect differences in test environments, or duplicate columns) may throw, which is expected
            logger.warn("[BankTransactionMigration] Statement '{}' failed (may be harmless if dialect differs): {}", sql, e.getMessage());
        }
    }
}
