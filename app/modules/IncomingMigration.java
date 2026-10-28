package modules;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Startup migration to ensure 'incoming' table has account_id, created_at, and archived_at columns
 * and backfills legacy incoming records with timestamps.
 */
@Singleton
public class IncomingMigration {

    @Inject
    public IncomingMigration(JPAApi jpaApi) {
        // 1. Ensure table exists
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(
                        "CREATE TABLE IF NOT EXISTS incoming (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "userId VARCHAR(255), " +
                        "name VARCHAR(255), " +
                        "type VARCHAR(255), " +
                        "grossValue REAL, " +
                        "netValue REAL, " +
                        "incomingMonthDay INT, " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "payDay BOOLEAN DEFAULT FALSE, " +
                        "account_id BIGINT, " +
                        "created_at BIGINT, " +
                        "archived_at BIGINT" +
                        ")"
                ).executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("IncomingMigration :: incoming table check: " + e.getMessage());
        }

        // 2. Ensure columns exist on incoming
        String[] cols = new String[]{
                "account_id BIGINT",
                "created_at BIGINT",
                "archived_at BIGINT"
        };
        for (String colDef : cols) {
            try {
                jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                    em.createNativeQuery("ALTER TABLE incoming ADD COLUMN IF NOT EXISTS " + colDef).executeUpdate();
                });
            } catch (Exception ignored) {}
        }

        // 3. Ensure foreign key from incoming.account_id to account.id if account table exists
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(
                        "DO $$ BEGIN " +
                        "IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_incoming_account') THEN " +
                        "ALTER TABLE incoming ADD CONSTRAINT fk_incoming_account FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE SET NULL; " +
                        "END IF; " +
                        "END $$;"
                ).executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("IncomingMigration :: foreign key check: " + e.getMessage());
        }

        // 4. Backfill created_at with 0L for legacy incoming records that have no created_at
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery("UPDATE incoming SET created_at = 0 WHERE created_at IS NULL").executeUpdate();
                em.createNativeQuery("UPDATE incoming SET archived_at = " + System.currentTimeMillis() + " WHERE archived = TRUE AND archived_at IS NULL").executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("IncomingMigration :: backfill check: " + e.getMessage());
        }
    }
}
