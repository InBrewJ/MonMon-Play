package modules;

import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Startup migration to ensure tables and columns for SavingsPlan and SavingsPlanItem exist.
 * Particularly ensures 'locked' and 'priority' columns exist on 'savings_plan_item'.
 */
@Singleton
public class SavingsPlanMigration {

    @Inject
    public SavingsPlanMigration(JPAApi jpaApi) {
        // 1. Ensure savings_plan table exists
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(
                        "CREATE TABLE IF NOT EXISTS savings_plan (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "savings_percentage REAL DEFAULT 0, " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "last_updated BIGINT" +
                        ")"
                ).executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("SavingsPlanMigration :: savings_plan table check: " + e.getMessage());
        }

        // 2. Ensure columns on savings_plan exist
        String[] planCols = new String[]{
                "user_id VARCHAR(255)",
                "savings_percentage REAL DEFAULT 0",
                "archived BOOLEAN DEFAULT FALSE",
                "last_updated BIGINT"
        };
        for (String colDef : planCols) {
            try {
                jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                    em.createNativeQuery("ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS " + colDef).executeUpdate();
                });
            } catch (Exception ignored) {}
        }

        // 3. Ensure savings_plan_item table exists
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(
                        "CREATE TABLE IF NOT EXISTS savings_plan_item (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "savings_plan_id BIGINT, " +
                        "name VARCHAR(255), " +
                        "type VARCHAR(50), " +
                        "account_id BIGINT, " +
                        "target_amount REAL DEFAULT 0, " +
                        "monthly_amount REAL DEFAULT 0, " +
                        "priority INTEGER DEFAULT 1, " +
                        "locked BOOLEAN DEFAULT FALSE, " +
                        "archived BOOLEAN DEFAULT FALSE" +
                        ")"
                ).executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("SavingsPlanMigration :: savings_plan_item table check: " + e.getMessage());
        }

        // 4. Ensure all columns on savings_plan_item exist
        String[] itemCols = new String[]{
                "savings_plan_id BIGINT",
                "name VARCHAR(255)",
                "type VARCHAR(50)",
                "account_id BIGINT",
                "target_amount REAL DEFAULT 0",
                "monthly_amount REAL DEFAULT 0",
                "priority INTEGER DEFAULT 1",
                "locked BOOLEAN DEFAULT FALSE",
                "archived BOOLEAN DEFAULT FALSE"
        };
        for (String colDef : itemCols) {
            try {
                jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                    em.createNativeQuery("ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS " + colDef).executeUpdate();
                });
            } catch (Exception e) {
                System.out.println("SavingsPlanMigration :: error adding column " + colDef + ": " + e.getMessage());
            }
        }

        // 5. Ensure outgoing table has outgoingmonth column
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery("ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS outgoingmonth INTEGER").executeUpdate();
            });
        } catch (Exception e) {
            System.out.println("SavingsPlanMigration :: outgoingmonth column check: " + e.getMessage());
        }

        System.out.println("SavingsPlanMigration :: completed schema verification for savings_plan, savings_plan_item, and outgoing");
    }
}
