package modules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.db.jpa.JPAApi;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Startup migration to create and maintain the unified PostgreSQL search view ('app_search_view')
 * and performance indexes covering all account primitives:
 * Accounts, Outgoings, Incomings, Balances, Pots, Savings Plans & Items, Meal Plans, Net Worth Assets,
 * Sharing Plans, Bank Transactions, and Pending Statements.
 *
 * Robust against legacy schema variations where user IDs were created as user_id, userid, or "userId".
 * Automatically standardizes columns, ensures both user_id and "userId" exist, keeps them in sync
 * via triggers, and registers fast search indexes.
 */
@Singleton
public class AppSearchMigration {

    private static final Logger logger = LoggerFactory.getLogger(AppSearchMigration.class);

    @Inject
    public AppSearchMigration(JPAApi jpaApi) {
        System.out.println("[AppSearchMigration] Initializing app-wide search view & indexes...");
        logger.info("[AppSearchMigration] Initializing app-wide search view & indexes...");

        // 1. Ensure all entity tables exist so the unified VIEW can reference them without error
        String[] tableDDLs = new String[]{
                "CREATE TABLE IF NOT EXISTS account (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "name VARCHAR(255), " +
                        "nickname VARCHAR(255), " +
                        "type VARCHAR(50), " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "available_limit REAL DEFAULT 0, " +
                        "interest_rate REAL DEFAULT 0, " +
                        "ledger_mode VARCHAR(32)" +
                        ")",
                "CREATE TABLE IF NOT EXISTS outgoing (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "name VARCHAR(255), " +
                        "cost REAL, " +
                        "\"OUTGOINGDAY\" INT, " +
                        "\"OUTGOINGMONTH\" INT, " +
                        "\"YEARINTERVAL\" INT DEFAULT 1, " +
                        "\"STARTINGYEAR\" INT, " +
                        "bill BOOLEAN DEFAULT FALSE, " +
                        "rent BOOLEAN DEFAULT FALSE, " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "paused BOOLEAN DEFAULT FALSE, " +
                        "pause_reason VARCHAR(255), " +
                        "paused_at BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS incoming (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
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
                        ")",
                "CREATE TABLE IF NOT EXISTS balance (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "value REAL, " +
                        "timestamp BIGINT, " +
                        "account_id BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS pot (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "type VARCHAR(50), " +
                        "spending_plan_name VARCHAR(255), " +
                        "spending_plan_food REAL, " +
                        "spending_plan_fuel REAL, " +
                        "spending_plan_transport REAL, " +
                        "spending_plan_fun REAL, " +
                        "spending_plan_sections_json TEXT, " +
                        "created_at BIGINT, " +
                        "last_updated BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS savings_plan (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "name VARCHAR(255), " +
                        "savings_percentage REAL DEFAULT 0, " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "last_updated BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS savings_plan_item (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "savings_plan_id BIGINT, " +
                        "name VARCHAR(255), " +
                        "type VARCHAR(50), " +
                        "account_id BIGINT, " +
                        "target_amount REAL DEFAULT 0, " +
                        "monthly_amount REAL DEFAULT 0, " +
                        "priority INT DEFAULT 1, " +
                        "locked BOOLEAN DEFAULT FALSE, " +
                        "archived BOOLEAN DEFAULT FALSE" +
                        ")",
                "CREATE TABLE IF NOT EXISTS locked_meal_plan (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "\"weeklyBudget\" REAL, " +
                        "people INT, " +
                        "postcode VARCHAR(50), " +
                        "\"planJson\" TEXT, " +
                        "\"lockedAt\" BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS net_worth_asset (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "name VARCHAR(255), " +
                        "category VARCHAR(50), " +
                        "asset_value DOUBLE PRECISION DEFAULT 0, " +
                        "notes VARCHAR(500), " +
                        "archived BOOLEAN DEFAULT FALSE, " +
                        "acquired_at BIGINT, " +
                        "created_at BIGINT, " +
                        "updated_at BIGINT" +
                        ")",
                "CREATE TABLE IF NOT EXISTS plan (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "type VARCHAR(50), " +
                        "split REAL, " +
                        "notes VARCHAR(500), " +
                        "archived BOOLEAN DEFAULT FALSE" +
                        ")",
                "CREATE TABLE IF NOT EXISTS bank_transaction (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "userid VARCHAR(255), " +
                        "account_id BIGINT, " +
                        "external_id VARCHAR(255), " +
                        "timestamp BIGINT, " +
                        "date VARCHAR(64), " +
                        "amount REAL DEFAULT 0, " +
                        "description VARCHAR(500), " +
                        "merchant_name VARCHAR(255), " +
                        "category VARCHAR(255), " +
                        "source VARCHAR(64)" +
                        ")",
                "CREATE TABLE IF NOT EXISTS pending_statement (" +
                        "id BIGSERIAL PRIMARY KEY, " +
                        "user_id VARCHAR(255), " +
                        "\"userId\" VARCHAR(255), " +
                        "bank_name VARCHAR(255), " +
                        "status VARCHAR(50), " +
                        "start_date VARCHAR(64), " +
                        "end_date VARCHAR(64), " +
                        "opening_balance REAL, " +
                        "closing_balance REAL" +
                        ")"
        };
        for (String sql : tableDDLs) {
            runSingleStatement(jpaApi, sql);
        }

        // 2. Standardize user ID columns across ALL tables:
        // Ensure both user_id and "userId" columns exist, and backfill across user_id, "userId", and legacy userid.
        String standardizeUserColumnsSql =
                "DO $$\n" +
                "DECLARE\n" +
                "    tbl text;\n" +
                "    tables text[] := ARRAY['account', 'outgoing', 'incoming', 'balance', 'pot', 'savings_plan', 'locked_meal_plan', 'net_worth_asset', 'plan', 'bank_transaction', 'pending_statement'];\n" +
                "BEGIN\n" +
                "    FOREACH tbl IN ARRAY tables LOOP\n" +
                "        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = tbl) THEN\n" +
                "            -- Ensure both user_id and \"userId\" columns exist\n" +
                "            EXECUTE 'ALTER TABLE ' || tbl || ' ADD COLUMN IF NOT EXISTS user_id VARCHAR(255)';\n" +
                "            EXECUTE 'ALTER TABLE ' || tbl || ' ADD COLUMN IF NOT EXISTS \"userId\" VARCHAR(255)';\n" +
                "\n" +
                "            -- Bidirectional backfill between user_id and \"userId\"\n" +
                "            EXECUTE 'UPDATE ' || tbl || ' SET user_id = \"userId\" WHERE user_id IS NULL AND \"userId\" IS NOT NULL';\n" +
                "            EXECUTE 'UPDATE ' || tbl || ' SET \"userId\" = user_id WHERE \"userId\" IS NULL AND user_id IS NOT NULL';\n" +
                "\n" +
                "            -- Backfill from legacy unquoted lowercase 'userid' if present\n" +
                "            IF EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema = 'public' AND table_name = tbl AND column_name = 'userid') THEN\n" +
                "                EXECUTE 'UPDATE ' || tbl || ' SET user_id = userid WHERE user_id IS NULL AND userid IS NOT NULL';\n" +
                "                EXECUTE 'UPDATE ' || tbl || ' SET \"userId\" = userid WHERE \"userId\" IS NULL AND userid IS NOT NULL';\n" +
                "            END IF;\n" +
                "        END IF;\n" +
                "    END LOOP;\n" +
                "END $$;";
        runSingleStatement(jpaApi, standardizeUserColumnsSql);

        // 3. Ensure other required columns exist on tables (safely ignore if already present)
        String[] alterColumns = new String[]{
                "ALTER TABLE account ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE account ADD COLUMN IF NOT EXISTS nickname VARCHAR(255)",
                "ALTER TABLE account ADD COLUMN IF NOT EXISTS type VARCHAR(50)",
                "ALTER TABLE account ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS cost REAL DEFAULT 0",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS \"OUTGOINGDAY\" INT",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS \"OUTGOINGMONTH\" INT",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS paused BOOLEAN DEFAULT FALSE",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS pause_reason VARCHAR(255)",
                "ALTER TABLE outgoing ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS type VARCHAR(255)",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS netValue REAL DEFAULT 0",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS grossValue REAL DEFAULT 0",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS incomingMonthDay INT",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS payDay BOOLEAN DEFAULT FALSE",
                "ALTER TABLE incoming ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE pot ADD COLUMN IF NOT EXISTS spending_plan_name VARCHAR(255)",
                "ALTER TABLE pot ADD COLUMN IF NOT EXISTS type VARCHAR(50)",
                "ALTER TABLE pot ADD COLUMN IF NOT EXISTS spending_plan_food REAL",
                "ALTER TABLE pot ADD COLUMN IF NOT EXISTS spending_plan_fun REAL",
                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS savings_percentage REAL DEFAULT 0",
                "ALTER TABLE savings_plan ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS type VARCHAR(50)",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS target_amount REAL DEFAULT 0",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS monthly_amount REAL DEFAULT 0",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS priority INT DEFAULT 1",
                "ALTER TABLE savings_plan_item ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS \"weeklyBudget\" REAL",
                "ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS people INT",
                "ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS postcode VARCHAR(50)",
                "ALTER TABLE locked_meal_plan ADD COLUMN IF NOT EXISTS \"planJson\" TEXT",
                "ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS name VARCHAR(255)",
                "ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS category VARCHAR(50)",
                "ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS asset_value DOUBLE PRECISION DEFAULT 0",
                "ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS notes VARCHAR(500)",
                "ALTER TABLE net_worth_asset ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE plan ADD COLUMN IF NOT EXISTS type VARCHAR(50)",
                "ALTER TABLE plan ADD COLUMN IF NOT EXISTS split REAL",
                "ALTER TABLE plan ADD COLUMN IF NOT EXISTS notes VARCHAR(500)",
                "ALTER TABLE plan ADD COLUMN IF NOT EXISTS archived BOOLEAN DEFAULT FALSE",
                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS merchant_name VARCHAR(255)",
                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS description VARCHAR(500)",
                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS category VARCHAR(255)",
                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS amount REAL DEFAULT 0",
                "ALTER TABLE bank_transaction ADD COLUMN IF NOT EXISTS date VARCHAR(64)",
                "ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS bank_name VARCHAR(255)",
                "ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS status VARCHAR(50)",
                "ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS start_date VARCHAR(64)",
                "ALTER TABLE pending_statement ADD COLUMN IF NOT EXISTS end_date VARCHAR(64)"
        };
        for (String sql : alterColumns) {
            runSingleStatement(jpaApi, sql);
        }

        // 4. Create trigger to automatically keep user_id and "userId" synchronized upon INSERT/UPDATE
        String createSyncTriggerSql =
                "CREATE OR REPLACE FUNCTION sync_user_id_columns()\n" +
                "RETURNS TRIGGER AS $$\n" +
                "BEGIN\n" +
                "    IF NEW.user_id IS NULL AND NEW.\"userId\" IS NOT NULL THEN\n" +
                "        NEW.user_id := NEW.\"userId\";\n" +
                "    ELSIF NEW.\"userId\" IS NULL AND NEW.user_id IS NOT NULL THEN\n" +
                "        NEW.\"userId\" := NEW.user_id;\n" +
                "    END IF;\n" +
                "    RETURN NEW;\n" +
                "END;\n" +
                "$$ LANGUAGE plpgsql;\n" +
                "\n" +
                "DO $$\n" +
                "DECLARE\n" +
                "    tbl text;\n" +
                "    tables text[] := ARRAY['account', 'outgoing', 'incoming', 'balance', 'pot', 'savings_plan', 'locked_meal_plan', 'net_worth_asset', 'plan', 'bank_transaction', 'pending_statement'];\n" +
                "BEGIN\n" +
                "    FOREACH tbl IN ARRAY tables LOOP\n" +
                "        IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = 'public' AND table_name = tbl) THEN\n" +
                "            EXECUTE 'DROP TRIGGER IF EXISTS trg_sync_user_id ON ' || tbl;\n" +
                "            EXECUTE 'CREATE TRIGGER trg_sync_user_id BEFORE INSERT OR UPDATE ON ' || tbl || ' FOR EACH ROW EXECUTE FUNCTION sync_user_id_columns()';\n" +
                "        END IF;\n" +
                "    END LOOP;\n" +
                "END $$;";
        runSingleStatement(jpaApi, createSyncTriggerSql);

        // 5. Ensure search performance indexes exist
        String[] indexes = new String[]{
                "CREATE INDEX IF NOT EXISTS idx_account_user_id ON account (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_account_name ON account (name)",
                "CREATE INDEX IF NOT EXISTS idx_outgoing_user_id ON outgoing (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_outgoing_name ON outgoing (name)",
                "CREATE INDEX IF NOT EXISTS idx_incoming_user_id ON incoming (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_incoming_name ON incoming (name)",
                "CREATE INDEX IF NOT EXISTS idx_balance_user_id ON balance (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_balance_acc ON balance (account_id)",
                "CREATE INDEX IF NOT EXISTS idx_pot_user_id ON pot (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_sp_user_id ON savings_plan (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_spi_name ON savings_plan_item (name)",
                "CREATE INDEX IF NOT EXISTS idx_spi_plan ON savings_plan_item (savings_plan_id)",
                "CREATE INDEX IF NOT EXISTS idx_mealplan_user_id ON locked_meal_plan (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_nw_asset_name ON net_worth_asset (name)",
                "CREATE INDEX IF NOT EXISTS idx_nw_asset_user_id ON net_worth_asset (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_plan_user_id ON plan (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_bt_user_id ON bank_transaction (user_id)",
                "CREATE INDEX IF NOT EXISTS idx_bt_search_merchant ON bank_transaction (merchant_name)",
                "CREATE INDEX IF NOT EXISTS idx_ps_user_id ON pending_statement (user_id)"
        };
        for (String sql : indexes) {
            runSingleStatement(jpaApi, sql);
        }

        // 6. Create or replace the unified PostgreSQL view
        String createViewSql =
                "CREATE OR REPLACE VIEW app_search_view AS " +
                // 1. Accounts
                "SELECT " +
                "  CAST('account' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(a.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(a.user_id, a.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(a.name, 'Account') AS VARCHAR(255)) AS title, " +
                "  CAST(COALESCE(a.type, 'Account') || CASE WHEN a.nickname IS NOT NULL AND a.nickname != '' THEN ' (' || a.nickname || ')' ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myAccounts?highlight=account-' || CAST(a.id AS VARCHAR) || '#account-' || CAST(a.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('account-' || CAST(a.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-university' AS VARCHAR(50)) AS icon, " +
                "  CAST('Account' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(a.name, '') || ' ' || COALESCE(a.nickname, '') || ' ' || COALESCE(a.type, '') || ' account') AS search_text " +
                "FROM account a " +
                "WHERE a.archived = false OR a.archived IS NULL " +

                "UNION ALL " +

                // 2. Outgoings
                "SELECT " +
                "  CAST('outgoing' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(o.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(o.user_id, o.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(o.name, 'Outgoing') AS VARCHAR(255)) AS title, " +
                "  CAST('£' || TO_CHAR(COALESCE(o.cost, 0), 'FM999999990.00') || ' • Day ' || CAST(COALESCE(o.\"OUTGOINGDAY\", 1) AS VARCHAR) || CASE WHEN o.paused = true THEN ' (Paused)' ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myOutgoings?highlight=outgoing-' || CAST(o.id AS VARCHAR) || '#outgoing-' || CAST(o.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('outgoing-' || CAST(o.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-credit-card' AS VARCHAR(50)) AS icon, " +
                "  CAST('Outgoing' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(o.name, '') || ' ' || COALESCE(o.pause_reason, '') || ' ' || CAST(COALESCE(o.cost, 0) AS VARCHAR) || ' outgoing bill rent') AS search_text " +
                "FROM outgoing o " +
                "WHERE o.archived = false OR o.archived IS NULL " +

                "UNION ALL " +

                // 3. Incomings
                "SELECT " +
                "  CAST('incoming' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(i.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(i.user_id, i.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(i.name, 'Incoming') AS VARCHAR(255)) AS title, " +
                "  CAST('£' || TO_CHAR(COALESCE(i.netValue, 0), 'FM999999990.00') || ' • Day ' || CAST(COALESCE(i.incomingMonthDay, 1) AS VARCHAR) || CASE WHEN i.payDay = true THEN ' (Payday)' ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myIncomings?highlight=incoming-' || CAST(i.id AS VARCHAR) || '#incoming-' || CAST(i.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('incoming-' || CAST(i.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-money-bill-wave' AS VARCHAR(50)) AS icon, " +
                "  CAST('Incoming' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(i.name, '') || ' ' || COALESCE(i.type, '') || ' ' || CAST(COALESCE(i.netValue, 0) AS VARCHAR) || ' incoming salary payday income') AS search_text " +
                "FROM incoming i " +
                "WHERE i.archived = false OR i.archived IS NULL " +

                "UNION ALL " +

                // 4. Balances
                "SELECT " +
                "  CAST('balance' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(b.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(b.user_id, b.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(acc.name, 'Account') || ' Balance' AS VARCHAR(255)) AS title, " +
                "  CAST('£' || TO_CHAR(COALESCE(b.value, 0), 'FM999999990.00') || CASE WHEN b.timestamp IS NOT NULL AND b.timestamp > 0 THEN ' (' || TO_CHAR(TO_TIMESTAMP(b.timestamp / 1000), 'DD Mon YYYY') || ')' ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myBalances?highlight=balance-' || CAST(b.id AS VARCHAR) || '#balance-' || CAST(b.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('balance-' || CAST(b.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-wallet' AS VARCHAR(50)) AS icon, " +
                "  CAST('Balance' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(acc.name, '') || ' balance ' || CAST(COALESCE(b.value, 0) AS VARCHAR)) AS search_text " +
                "FROM balance b " +
                "LEFT JOIN account acc ON b.account_id = acc.id " +

                "UNION ALL " +

                // 5. Pots
                "SELECT " +
                "  CAST('pot' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(p.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(p.user_id, p.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(p.spending_plan_name, COALESCE(p.type, 'Monthly') || ' Pot') AS VARCHAR(255)) AS title, " +
                "  CAST(COALESCE(p.type, 'Monthly') || ' Pot' || CASE WHEN p.spending_plan_food IS NOT NULL THEN ' • Food £' || CAST(p.spending_plan_food AS VARCHAR) ELSE '' END || CASE WHEN p.spending_plan_fun IS NOT NULL THEN ', Fun £' || CAST(p.spending_plan_fun AS VARCHAR) ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myMonthlyPot?highlight=pot-' || CAST(p.id AS VARCHAR) || '#pot-' || CAST(p.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('pot-' || CAST(p.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-archive' AS VARCHAR(50)) AS icon, " +
                "  CAST('Pot' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(p.spending_plan_name, '') || ' ' || COALESCE(p.type, '') || ' pot spending plan food fun fuel') AS search_text " +
                "FROM pot p " +

                "UNION ALL " +

                // 6. Savings Plan
                "SELECT " +
                "  CAST('savings_plan' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(sp.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(sp.user_id, sp.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(sp.name, 'Monthly Savings Plan') AS VARCHAR(255)) AS title, " +
                "  CAST(CAST(COALESCE(sp.savings_percentage, 0) AS VARCHAR) || '% of net income target pot' AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/savingsPlan?highlight=savings-plan-' || CAST(sp.id AS VARCHAR) || '#savings-plan-' || CAST(sp.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('savings-plan-' || CAST(sp.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-piggy-bank' AS VARCHAR(50)) AS icon, " +
                "  CAST('Savings Plan' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(sp.name, '') || ' savings plan ' || CAST(COALESCE(sp.savings_percentage, 0) AS VARCHAR) || '%') AS search_text " +
                "FROM savings_plan sp " +
                "WHERE sp.archived = false OR sp.archived IS NULL " +

                "UNION ALL " +

                // 7. Savings Plan Item
                "SELECT " +
                "  CAST('savings_plan_item' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(spi.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(sp.user_id, sp.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(spi.name, 'Savings Target') AS VARCHAR(255)) AS title, " +
                "  CAST(COALESCE(spi.type, 'SAVINGS') || ' • P' || CAST(COALESCE(spi.priority, 1) AS VARCHAR) || ' • £' || TO_CHAR(COALESCE(spi.monthly_amount, 0), 'FM999999990.00') || '/mo (Target £' || TO_CHAR(COALESCE(spi.target_amount, 0), 'FM999999990.00') || ')' AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/savingsPlan?highlight=savings-item-' || CAST(spi.id AS VARCHAR) || '#savings-item-' || CAST(spi.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('savings-item-' || CAST(spi.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-bullseye' AS VARCHAR(50)) AS icon, " +
                "  CAST('Savings Item' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(spi.name, '') || ' ' || COALESCE(spi.type, '') || ' savings debt payoff target priority ' || CAST(COALESCE(spi.monthly_amount, 0) AS VARCHAR)) AS search_text " +
                "FROM savings_plan_item spi " +
                "JOIN savings_plan sp ON spi.savings_plan_id = sp.id " +
                "WHERE (spi.archived = false OR spi.archived IS NULL) AND (sp.archived = false OR sp.archived IS NULL) " +

                "UNION ALL " +

                // 8. Meal Plan (NomMon)
                "SELECT " +
                "  CAST('meal_plan' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(mp.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(mp.user_id, mp.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST('Weekly Meal Plan (NomMon)' AS VARCHAR(255)) AS title, " +
                "  CAST('£' || TO_CHAR(COALESCE(mp.\"weeklyBudget\", 0), 'FM999999990.00') || ' budget • ' || CAST(COALESCE(mp.people, 1) AS VARCHAR) || ' people' || CASE WHEN mp.postcode IS NOT NULL AND mp.postcode != '' THEN ' • ' || mp.postcode ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/nommon?highlight=nommon-content#nommon-content' AS VARCHAR(255)) AS target_url, " +
                "  CAST('nommon-content' AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-utensils' AS VARCHAR(50)) AS icon, " +
                "  CAST('Meal Plan' AS VARCHAR(50)) AS badge, " +
                "  LOWER('meal plan grocery weekly budget nommon recipe food shopping ingredients ' || COALESCE(mp.postcode, '') || ' ' || COALESCE(mp.\"planJson\", '')) AS search_text " +
                "FROM locked_meal_plan mp " +

                "UNION ALL " +

                // 9. Net Worth Assets
                "SELECT " +
                "  CAST('net_worth_asset' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(nwa.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(nwa.user_id, nwa.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(nwa.name, 'Asset') AS VARCHAR(255)) AS title, " +
                "  CAST(COALESCE(nwa.category, 'OTHER') || ' • £' || TO_CHAR(COALESCE(nwa.asset_value, 0), 'FM999999990.00') || CASE WHEN nwa.notes IS NOT NULL AND nwa.notes != '' THEN ' • ' || nwa.notes ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/netWorth?highlight=asset-' || CAST(nwa.id AS VARCHAR) || '#asset-' || CAST(nwa.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('asset-' || CAST(nwa.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-gem' AS VARCHAR(50)) AS icon, " +
                "  CAST('Asset' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(nwa.name, '') || ' ' || COALESCE(nwa.category, '') || ' ' || COALESCE(nwa.notes, '') || ' asset net worth ' || CAST(COALESCE(nwa.asset_value, 0) AS VARCHAR)) AS search_text " +
                "FROM net_worth_asset nwa " +
                "WHERE nwa.archived = false OR nwa.archived IS NULL " +

                "UNION ALL " +

                // 10. Sharing Plan
                "SELECT " +
                "  CAST('plan' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(pl.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(pl.user_id, pl.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(CASE WHEN pl.type = 'BILL_SHARE' THEN 'Bill Sharing Plan' WHEN pl.type = 'RENT_SHARE' THEN 'Rent Sharing Plan' ELSE COALESCE(pl.type, 'Sharing Plan') END AS VARCHAR(255)) AS title, " +
                "  CAST('Split: ' || TO_CHAR(COALESCE(pl.split, 0) * 100, 'FM990.0') || '%' || CASE WHEN pl.notes IS NOT NULL AND pl.notes != '' THEN ' • ' || pl.notes ELSE '' END AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/myPlans?highlight=plan-' || CAST(pl.id AS VARCHAR) || '#plan-' || CAST(pl.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('plan-' || CAST(pl.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-sliders-h' AS VARCHAR(50)) AS icon, " +
                "  CAST('Sharing Plan' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(pl.type, '') || ' ' || COALESCE(pl.notes, '') || ' sharing plan split bills rent') AS search_text " +
                "FROM plan pl " +
                "WHERE pl.archived = false OR pl.archived IS NULL " +

                "UNION ALL " +

                // 11. Bank Transactions
                "SELECT " +
                "  CAST('bank_transaction' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(bt.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(bt.user_id, bt.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(bt.merchant_name, bt.description, 'Bank Transaction') AS VARCHAR(255)) AS title, " +
                "  CAST('£' || TO_CHAR(COALESCE(bt.amount, 0), 'FM999999990.00') || ' • ' || COALESCE(bt.date, '') || ' • ' || COALESCE(bt.category, 'General') AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/bank?highlight=tx-' || CAST(bt.id AS VARCHAR) || '#tx-' || CAST(bt.id AS VARCHAR) AS VARCHAR(255)) AS target_url, " +
                "  CAST('tx-' || CAST(bt.id AS VARCHAR) AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-receipt' AS VARCHAR(50)) AS icon, " +
                "  CAST('Transaction' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(bt.merchant_name, '') || ' ' || COALESCE(bt.description, '') || ' ' || COALESCE(bt.category, '') || ' ' || CAST(COALESCE(bt.amount, 0) AS VARCHAR) || ' transaction bank') AS search_text " +
                "FROM bank_transaction bt " +

                "UNION ALL " +

                // 12. Pending Statements
                "SELECT " +
                "  CAST('pending_statement' AS VARCHAR(50)) AS entity_type, " +
                "  CAST(ps.id AS VARCHAR) AS entity_id, " +
                "  CAST(COALESCE(ps.user_id, ps.\"userId\", '') AS VARCHAR(255)) AS user_id, " +
                "  CAST(COALESCE(ps.bank_name, 'Bank') || ' Statement' AS VARCHAR(255)) AS title, " +
                "  CAST(COALESCE(ps.status, 'PENDING') || ' • ' || COALESCE(ps.start_date, '') || ' to ' || COALESCE(ps.end_date, '') AS VARCHAR(255)) AS subtitle, " +
                "  CAST('/bank?highlight=card-bank-statements#card-bank-statements' AS VARCHAR(255)) AS target_url, " +
                "  CAST('card-bank-statements' AS VARCHAR(100)) AS dom_id, " +
                "  CAST('fa-file-invoice' AS VARCHAR(50)) AS icon, " +
                "  CAST('Statement' AS VARCHAR(50)) AS badge, " +
                "  LOWER(COALESCE(ps.bank_name, '') || ' statement pdf import ' || COALESCE(ps.status, '')) AS search_text " +
                "FROM pending_statement ps";

        runSingleStatement(jpaApi, createViewSql);

        // 7. Verify view exists
        try {
            boolean viewExists = jpaApi.withTransaction((java.util.function.Function<javax.persistence.EntityManager, Boolean>) em -> {
                Number count = (Number) em.createNativeQuery(
                        "SELECT COUNT(*) FROM information_schema.views WHERE table_schema = 'public' AND table_name = 'app_search_view'"
                ).getSingleResult();
                return count != null && count.intValue() > 0;
            });
            if (viewExists) {
                System.out.println("[AppSearchMigration] app_search_view verified successfully in PostgreSQL.");
                logger.info("[AppSearchMigration] app_search_view verified successfully in PostgreSQL.");
            } else {
                System.err.println("[AppSearchMigration] WARNING: app_search_view was not found in PostgreSQL!");
                logger.error("[AppSearchMigration] WARNING: app_search_view was not found in PostgreSQL!");
            }
        } catch (Exception e) {
            logger.warn("[AppSearchMigration] View verification notice: {}", e.getMessage());
        }
    }

    private void runSingleStatement(JPAApi jpaApi, String sql) {
        try {
            jpaApi.withTransaction((java.util.function.Consumer<javax.persistence.EntityManager>) em -> {
                em.createNativeQuery(sql).executeUpdate();
            });
            logger.debug("[AppSearchMigration] Executed: {}", sql);
        } catch (Exception e) {
            String snippet = sql.length() > 80 ? sql.substring(0, 80).replace('\n', ' ') + "..." : sql.replace('\n', ' ');
            System.err.println("[AppSearchMigration] Notice for statement '" + snippet + "': " + e.getMessage());
            logger.warn("[AppSearchMigration] Notice for statement '{}': {}", snippet, e.getMessage());
        }
    }
}
