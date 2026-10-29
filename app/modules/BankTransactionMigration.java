package modules;

import org.hibernate.Session;
import play.db.jpa.JPAApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.sql.Statement;

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
        System.out.println("[BankTransactionMigration] Starting BankTransaction schema migration...");
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

        // 2. Standardize column names (Hibernate entity uses "userId" and "accountId", legacy DDL uses user_id / userid / accountid)
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)");
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS \"userId\" VARCHAR(255)");
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS account_id BIGINT");
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS \"accountId\" BIGINT");

        runSingleStatement(jpaApi,
                "DO $$\n" +
                "BEGIN\n" +
                "    IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'bank_transaction' AND column_name = 'accountid') THEN\n" +
                "        UPDATE bank_transaction SET account_id = accountid WHERE account_id IS NULL AND accountid IS NOT NULL;\n" +
                "        UPDATE bank_transaction SET \"accountId\" = accountid WHERE \"accountId\" IS NULL AND accountid IS NOT NULL;\n" +
                "    END IF;\n" +
                "    UPDATE bank_transaction SET account_id = \"accountId\" WHERE account_id IS NULL AND \"accountId\" IS NOT NULL;\n" +
                "    UPDATE bank_transaction SET \"accountId\" = account_id WHERE \"accountId\" IS NULL AND account_id IS NOT NULL;\n" +
                "END $$;");

        // 3. Ensure indexes
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_uid ON bank_transaction (\"userId\")");
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_acc ON bank_transaction (\"accountId\")");
        runSingleStatement(jpaApi, "CREATE INDEX IF NOT EXISTS idx_bt_time ON bank_transaction (timestamp)");

        // 4. Ensure extra columns exist
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

        // 5. Critically: Ensure raw_data is of type TEXT in case it was created as jsonb or varchar.
        runSingleStatement(jpaApi, "ALTER TABLE bank_transaction ALTER COLUMN raw_data TYPE TEXT USING CAST(raw_data AS TEXT)");

        System.out.println("[BankTransactionMigration] BankTransaction schema migration completed successfully.");
        logger.info("[BankTransactionMigration] BankTransaction schema migration completed successfully.");
    }

    private void runSingleStatement(JPAApi jpaApi, String sql) {
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                Session session = em.unwrap(Session.class);
                session.doWork(connection -> {
                    try (Statement stmt = connection.createStatement()) {
                        stmt.execute(sql);
                    }
                });
            });
            logger.debug("[BankTransactionMigration] Executed: {}", sql);
        } catch (Exception e) {
            System.out.println("[BankTransactionMigration] Notice for statement '" + sql + "': " + e.getMessage());
            logger.warn("[BankTransactionMigration] Statement '{}' notice: {}", sql, e.getMessage());
        }
    }
}
