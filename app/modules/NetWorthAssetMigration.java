package modules;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Startup migration to ensure net_worth_asset table has created_at, updated_at, and deleted_at columns
 * and backfills all existing non-timestamped fixed assets with today's date.
 */
@Singleton
public class NetWorthAssetMigration {

    @Inject
    public NetWorthAssetMigration(JPAApi jpaApi) {
        long todayMs = System.currentTimeMillis();

        // 1. Ensure table exists
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(
                        "CREATE TABLE IF NOT EXISTS net_worth_asset (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "userId VARCHAR(255) NOT NULL, " +
                        "name VARCHAR(255) NOT NULL, " +
                        "category VARCHAR(50) NOT NULL DEFAULT 'OTHER', " +
                        "asset_value DOUBLE PRECISION NOT NULL DEFAULT 0, " +
                        "notes VARCHAR(255), " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "created_at BIGINT, " +
                        "updated_at BIGINT, " +
                        "deleted_at BIGINT" +
                        ")"
                ).executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("NetWorthAssetMigration :: net_worth_asset table check: " + e.getMessage());
        }

        // 2. Ensure columns exist on net_worth_asset
        String[] cols = new String[]{
                "created_at BIGINT",
                "updated_at BIGINT",
                "deleted_at BIGINT",
                "acquired_at BIGINT",
                "createdat BIGINT",
                "updatedat BIGINT",
                "acquiredat BIGINT"
        };
        for (String colDef : cols) {
            try {
                jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                    em.createNativeQuery("ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS " + colDef).executeUpdate();
                });
            } catch (Exception ignored) {}
        }

        // 3. Backfill all existing non-timestamped fixed assets with today's date
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET created_at = " + todayMs + " WHERE created_at IS NULL OR created_at = 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET updated_at = " + todayMs + " WHERE updated_at IS NULL OR updated_at = 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET createdat = " + todayMs + " WHERE createdat IS NULL OR createdat = 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET updatedat = " + todayMs + " WHERE updatedat IS NULL OR updatedat = 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET acquired_at = created_at WHERE (acquired_at IS NULL OR acquired_at = 0) AND created_at IS NOT NULL AND created_at > 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET acquired_at = " + todayMs + " WHERE acquired_at IS NULL OR acquired_at = 0").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET acquiredat = acquired_at WHERE (acquiredat IS NULL OR acquiredat = 0) AND acquired_at IS NOT NULL").executeUpdate();
                } catch (Exception ignored) {}
                try {
                    em.createNativeQuery("UPDATE net_worth_asset SET deleted_at = " + todayMs + " WHERE archived = true AND (deleted_at IS NULL OR deleted_at = 0)").executeUpdate();
                } catch (Exception ignored) {}
            });
            System.out.println("NetWorthAssetMigration :: completed schema verification and backfilled non-timestamped assets with timestamp " + todayMs);
        } catch (Exception e) {
            System.out.println("NetWorthAssetMigration :: backfill notice: " + e.getMessage());
        }
    }
}
