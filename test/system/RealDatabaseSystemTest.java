package system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import controllers.SavingsPlanController;
import models.*;
import org.junit.*;
import org.junit.runners.MethodSorters;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.Application;
import play.inject.guice.GuiceApplicationBuilder;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static play.test.Helpers.contentAsString;
import static play.test.Helpers.route;

/**
 * End-to-End System Tests running against a real PostgreSQL database instance.
 *
 * Covers:
 * 1. Savings Plan 15-item priority queue operations persisted in real PostgreSQL:
 *    - 15 items list creation & contiguous priority verification (P1..P15)
 *    - Moving items up or down 1 position using the buttons (moveUp / moveDown)
 *    - Moving multiple items to the very top (moveToTop) or very bottom (moveToBottom)
 *    - Deleting P5 with a monthly value -> rolls value into P1, shifts P6..P15 -> P5..P14
 *    - Deleting P1 -> rolls all of P1's value into P2 (new P1), and cascading again when new P1 is deleted
 *    - Reassigning P7 as P5 -> P7 becomes P5, old P5 becomes P6, old P6 becomes P7, etc.
 * 2. PostgreSQL JSONB -> TEXT schema migration and end-to-end statement webhook ingestion & confirmation.
 * 3. Monzo long-term Confidential OAuth Client persistence in PostgreSQL.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class RealDatabaseSystemTest {

    private static final String PG_HOST_URL = System.getenv().getOrDefault("SYSTEST_PG_ADMIN_URL", "jdbc:postgresql://127.0.0.1:5432/postgres");
    private static final String SYSTEST_DB = System.getenv().getOrDefault("SYSTEST_DB_NAME", "monmon_systest");
    private static final String SYSTEST_DB_URL = System.getenv().getOrDefault("SYSTEST_DB_URL", "jdbc:postgresql://127.0.0.1:5432/" + SYSTEST_DB);
    private static final String PG_USER = System.getenv().getOrDefault("SYSTEST_DB_USER", "postgres");
    private static final String PG_PASS = System.getenv().getOrDefault("SYSTEST_DB_PASSWORD", "docker");

    private static Application app;
    private static SavingsPlanRepository savingsPlanRepo;
    private static AccountRepository accountRepo;
    private static OutgoingRepository outgoingRepo;
    private static MonzoConnectionRepository monzoConnRepo;

    @BeforeClass
    public static void setUpRealDatabaseAndApplication() throws Exception {
        // 1. Verify real PostgreSQL is reachable and recreate fresh monmon_systest database
        try (Connection adminConn = DriverManager.getConnection(PG_HOST_URL, PG_USER, PG_PASS);
             Statement stmt = adminConn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + SYSTEST_DB + "' AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS " + SYSTEST_DB);
            stmt.execute("CREATE DATABASE " + SYSTEST_DB);
        }

        // 2. Seed the legacy JSONB bug schema on bank_transaction BEFORE Play starts
        // so we verify BankTransactionMigration automatically converts raw_data from jsonb to text on startup
        try (Connection dbConn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             Statement stmt = dbConn.createStatement()) {
            stmt.execute("CREATE TABLE bank_transaction (" +
                    "id BIGSERIAL PRIMARY KEY, " +
                    "userid VARCHAR(255), " +
                    "user_id VARCHAR(255), " +
                    "\"userId\" VARCHAR(255), " +
                    "accountid BIGINT, " +
                    "account_id BIGINT, " +
                    "externalid VARCHAR(255) UNIQUE, " +
                    "external_id VARCHAR(255), " +
                    "timestamp BIGINT, " +
                    "date VARCHAR(64), " +
                    "amount REAL DEFAULT 0, " +
                    "description VARCHAR(500), " +
                    "merchantname VARCHAR(255), " +
                    "merchant_name VARCHAR(255), " +
                    "category VARCHAR(255), " +
                    "merchantaddress VARCHAR(500), " +
                    "merchant_address VARCHAR(500), " +
                    "latitude DOUBLE PRECISION, " +
                    "longitude DOUBLE PRECISION, " +
                    "merchantemoji VARCHAR(50), " +
                    "merchant_emoji VARCHAR(50), " +
                    "source VARCHAR(64) DEFAULT 'MANUAL', " +
                    "pending BOOLEAN DEFAULT FALSE, " +
                    "createdat BIGINT, " +
                    "created_at BIGINT, " +
                    "raw_data JSONB" +
                    ")");
        }

        // 3. Boot Play Application wired to the real PostgreSQL database
        Map<String, Object> config = new HashMap<>();
        config.put("db.default.driver", "org.postgresql.Driver");
        config.put("db.default.url", SYSTEST_DB_URL);
        config.put("db.default.username", PG_USER);
        config.put("db.default.password", PG_PASS);
        config.put("db.default.jndiName", "DefaultDS");
        config.put("jpa.default", "defaultPersistenceUnit");
        config.put("play.filters.hosts.allowed", Arrays.asList("localhost", "127.0.0.1", "localhost:9000"));

        app = new GuiceApplicationBuilder()
                .configure(config)
                .build();
        Helpers.start(app);

        savingsPlanRepo = app.injector().instanceOf(SavingsPlanRepository.class);
        accountRepo = app.injector().instanceOf(AccountRepository.class);
        outgoingRepo = app.injector().instanceOf(OutgoingRepository.class);
        monzoConnRepo = app.injector().instanceOf(MonzoConnectionRepository.class);
    }

    @AfterClass
    public static void tearDownApplicationAndDatabase() throws Exception {
        JPAUserAccessRepository.clearInstance();
        if (app != null) {
            Helpers.stop(app);
        }
        try (Connection adminConn = DriverManager.getConnection(PG_HOST_URL, PG_USER, PG_PASS);
             Statement stmt = adminConn.createStatement()) {
            stmt.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '" + SYSTEST_DB + "' AND pid <> pg_backend_pid()");
            stmt.execute("DROP DATABASE IF EXISTS " + SYSTEST_DB);
        }
    }

    /**
     * Helper to create a fresh 15-item SavingsPlan in PostgreSQL for a given userId,
     * including a linked Account that has multiple Outgoings (to ensure EAGER join duplication never occurs).
     */
    private SavingsPlan createFifteenItemPlanInPostgres(String userId) throws Exception {
        // Create an account with 5 outgoings linked to it in PostgreSQL
        Account linkedAccount = new Account();
        linkedAccount.setUserId(userId);
        linkedAccount.setName("Main Current Account");
        linkedAccount.setType(Account.AccountType.DEBIT);
        linkedAccount.setAvailableLimit(500f);
        linkedAccount = accountRepo.add(linkedAccount).toCompletableFuture().get();

        for (int o = 1; o <= 5; o++) {
            Outgoing outgoing = new Outgoing();
            outgoing.setUserId(userId);
            outgoing.setName("Bill " + o);
            outgoing.setCost(25.0f * o);
            outgoing.setOutgoingDay(o);
            outgoing.setAccount(linkedAccount);
            outgoingRepo.add(outgoing).toCompletableFuture().get();
        }

        SavingsPlan plan = new SavingsPlan(userId, "15-Item System Test Plan", 25);
        plan = savingsPlanRepo.createOrReplace(userId, plan).toCompletableFuture().get();

        for (int i = 1; i <= 15; i++) {
            Account itemAcc = (i == 1) ? linkedAccount : null;
            float monthlyVal = i * 10.0f; // Item 1 = £10, Item 2 = £20, ..., Item 5 = £50, ..., Item 15 = £150
            SavingsPlanItem item = new SavingsPlanItem(
                    "Item " + i,
                    SavingsPlanItem.ItemType.SAVINGS_TARGET,
                    itemAcc,
                    1000.0f,
                    monthlyVal,
                    i
            );
            savingsPlanRepo.addItem(plan.getId(), item).toCompletableFuture().get();
        }

        SavingsPlan loaded = savingsPlanRepo.getActive(userId).toCompletableFuture().get();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getItems()).hasSize(15);
        return loaded;
    }

    /**
     * Asserts that the active items in PostgreSQL (both via JPA getActive and direct JDBC SQL query)
     * match the exact expected item names at positions P1..PN.
     */
    private List<SavingsPlanItem> assertPositionsInPostgres(String userId, Long planId, String... expectedNames) throws Exception {
        // 1. Assert via JPA Repository (fresh EntityManager query)
        SavingsPlan freshPlan = savingsPlanRepo.getActive(userId).toCompletableFuture().get();
        assertThat(freshPlan).isNotNull();
        List<SavingsPlanItem> activeItems = freshPlan.getItems();
        assertThat(activeItems)
                .as("Active item count in JPA for user " + userId)
                .hasSize(expectedNames.length);

        for (int idx = 0; idx < expectedNames.length; idx++) {
            int expectedPriority = idx + 1;
            SavingsPlanItem item = activeItems.get(idx);
            assertThat(item.getName())
                    .as("Item name at position P" + expectedPriority)
                    .isEqualTo(expectedNames[idx]);
            assertThat(item.getPriority())
                    .as("Item priority at position P" + expectedPriority + " (" + item.getName() + ")")
                    .isEqualTo(expectedPriority);
        }

        // 2. Assert directly against raw PostgreSQL rows via JDBC to guarantee DB state matches 100%
        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT name, priority FROM savings_plan_item WHERE savings_plan_id = ? AND (archived IS NULL OR archived = false) ORDER BY priority ASC, id ASC")) {
            ps.setLong(1, planId);
            try (ResultSet rs = ps.executeQuery()) {
                int idx = 0;
                while (rs.next()) {
                    assertThat(idx).isLessThan(expectedNames.length);
                    String dbName = rs.getString("name");
                    int dbPriority = rs.getInt("priority");
                    assertThat(dbName)
                            .as("Raw PostgreSQL row name at P" + (idx + 1))
                            .isEqualTo(expectedNames[idx]);
                    assertThat(dbPriority)
                            .as("Raw PostgreSQL row priority for " + dbName)
                            .isEqualTo(idx + 1);
                    idx++;
                }
                assertThat(idx)
                        .as("Raw PostgreSQL active row count")
                        .isEqualTo(expectedNames.length);
            }
        }

        return activeItems;
    }

    private SavingsPlanController buildAuthenticatedController(String userId) throws Exception {
        SavingsPlanController controller = app.injector().instanceOf(SavingsPlanController.class);
        SessionStore mockSessionStore = mock(SessionStore.class);

        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", userId);
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);

        when(mockSessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
        when(mockSessionStore.get(any(), eq("user_access_status"))).thenReturn(Optional.of(UserAccess.AccessStatus.APPROVED));

        Field f = SavingsPlanController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, mockSessionStore);
        return controller;
    }

    @Test
    public void test01_FifteenItemsMoveUpAndDownOnePositionUsingButtons() throws Exception {
        String userId = "systest_user_move_15";
        SavingsPlan plan = createFifteenItemPlanInPostgres(userId);
        Long planId = plan.getId();
        SavingsPlanController controller = buildAuthenticatedController(userId);

        // Assert initial positions P1..P15
        List<SavingsPlanItem> initial = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        Long item8Id = initial.get(7).getId(); // P8 = Item 8
        assertThat(initial.get(7).getName()).isEqualTo("Item 8");

        // 1. Move Item 8 (P8) UP one position using the controller button action -> becomes P7, Item 7 becomes P8
        Http.Request reqUp1 = Helpers.fakeRequest("POST", "/savingsPlan/moveUp/" + item8Id).build();
        Result resUp1 = controller.moveItemUp(item8Id, reqUp1).toCompletableFuture().get();
        assertThat(resUp1.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 8", "Item 7", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // 2. Move Item 8 (now P7) UP one position again -> becomes P6, Item 6 becomes P7
        Http.Request reqUp2 = Helpers.fakeRequest("POST", "/savingsPlan/moveUp/" + item8Id).build();
        Result resUp2 = controller.moveItemUp(item8Id, reqUp2).toCompletableFuture().get();
        assertThat(resUp2.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 8", "Item 6", "Item 7", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // 3. Move Item 8 (now P6) DOWN one position using the button -> becomes P7, Item 6 returns to P6
        Http.Request reqDown1 = Helpers.fakeRequest("POST", "/savingsPlan/moveDown/" + item8Id).build();
        Result resDown1 = controller.moveItemDown(item8Id, reqDown1).toCompletableFuture().get();
        assertThat(resDown1.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 8", "Item 7", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // 4. Move Item 8 (now P7) DOWN one position again -> back to P8, Item 7 back to P7
        Http.Request reqDown2 = Helpers.fakeRequest("POST", "/savingsPlan/moveDown/" + item8Id).build();
        Result resDown2 = controller.moveItemDown(item8Id, reqDown2).toCompletableFuture().get();
        assertThat(resDown2.status()).isEqualTo(303);

        List<SavingsPlanItem> restored = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // 5. Move Item 1 (P1) DOWN one position -> Item 2 becomes P1, Item 1 becomes P2
        Long item1Id = restored.get(0).getId();
        controller.moveItemDown(item1Id, Helpers.fakeRequest("POST", "/savingsPlan/moveDown/" + item1Id).build())
                .toCompletableFuture().get();

        assertPositionsInPostgres(userId, planId,
                "Item 2", "Item 1", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // Move Item 1 (P2) UP one position -> Item 1 back to P1
        controller.moveItemUp(item1Id, Helpers.fakeRequest("POST", "/savingsPlan/moveUp/" + item1Id).build())
                .toCompletableFuture().get();

        assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");
    }

    @Test
    public void test02_FifteenItemsMoveCoupleOfItemsToVeryTopAndVeryBottom() throws Exception {
        String userId = "systest_user_extremes_15";
        SavingsPlan plan = createFifteenItemPlanInPostgres(userId);
        Long planId = plan.getId();
        SavingsPlanController controller = buildAuthenticatedController(userId);

        List<SavingsPlanItem> items = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        Long item10Id = items.get(9).getId();  // Item 10 (P10)
        Long item14Id = items.get(13).getId(); // Item 14 (P14)
        Long item3Id = items.get(2).getId();   // Item 3 (P3)
        Long item6Id = items.get(5).getId();   // Item 6 (P6)

        // 1. Move Item 10 (P10) to the VERY TOP (P1)
        Result top1 = controller.moveToTop(item10Id, Helpers.fakeRequest("POST", "/savingsPlan/moveToTop/" + item10Id).build())
                .toCompletableFuture().get();
        assertThat(top1.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 10", "Item 1", "Item 2", "Item 3", "Item 4",
                "Item 5", "Item 6", "Item 7", "Item 8", "Item 9",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        // 2. Move Item 14 (P14) to the VERY TOP (P1)
        Result top2 = controller.moveToTop(item14Id, Helpers.fakeRequest("POST", "/savingsPlan/moveToTop/" + item14Id).build())
                .toCompletableFuture().get();
        assertThat(top2.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 14", "Item 10", "Item 1", "Item 2", "Item 3",
                "Item 4", "Item 5", "Item 6", "Item 7", "Item 8",
                "Item 9", "Item 11", "Item 12", "Item 13", "Item 15");

        // 3. Move Item 3 (currently P5) to the VERY BOTTOM (P15)
        Result bot1 = controller.moveToBottom(item3Id, Helpers.fakeRequest("POST", "/savingsPlan/moveToBottom/" + item3Id).build())
                .toCompletableFuture().get();
        assertThat(bot1.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 14", "Item 10", "Item 1", "Item 2", "Item 4",
                "Item 5", "Item 6", "Item 7", "Item 8", "Item 9",
                "Item 11", "Item 12", "Item 13", "Item 15", "Item 3");

        // 4. Move Item 6 (currently P7) to the VERY BOTTOM (P15)
        Result bot2 = controller.moveToBottom(item6Id, Helpers.fakeRequest("POST", "/savingsPlan/moveToBottom/" + item6Id).build())
                .toCompletableFuture().get();
        assertThat(bot2.status()).isEqualTo(303);

        assertPositionsInPostgres(userId, planId,
                "Item 14", "Item 10", "Item 1", "Item 2", "Item 4",
                "Item 5", "Item 7", "Item 8", "Item 9", "Item 11",
                "Item 12", "Item 13", "Item 15", "Item 3", "Item 6");
    }

    @Test
    public void test03_DeleteP5AddsValueToP1AndDeleteP1RollsValueToP2AndSoOn() throws Exception {
        String userId = "systest_user_delete_rollover";
        SavingsPlan plan = createFifteenItemPlanInPostgres(userId);
        Long planId = plan.getId();
        SavingsPlanController controller = buildAuthenticatedController(userId);

        // Initial values: Item 1 = £10, Item 2 = £20, Item 3 = £30, Item 4 = £40, Item 5 = £50, ..., Item 15 = £150
        // Total allocated = 10 + 20 + ... + 150 = £1200.00
        List<SavingsPlanItem> initial = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        assertThat(initial.get(0).getMonthlyAmount()).isEqualTo(10.0f); // P1 = £10
        assertThat(initial.get(1).getMonthlyAmount()).isEqualTo(20.0f); // P2 = £20
        assertThat(initial.get(2).getMonthlyAmount()).isEqualTo(30.0f); // P3 = £30
        assertThat(initial.get(4).getMonthlyAmount()).isEqualTo(50.0f); // P5 = £50

        // 1. Delete item in position 5 (Item 5 with value £50.00)
        Long item5Id = initial.get(4).getId();
        Result delP5 = controller.archiveItem(item5Id, Helpers.fakeRequest("GET", "/binSavingsPlanItem/" + item5Id).build())
                .toCompletableFuture().get();
        assertThat(delP5.status()).isEqualTo(303);

        // Verify Item 5 is deleted, its £50 value is added to P1 (Item 1: £10 + £50 = £60),
        // and Item 6..Item 15 shifted up to P5..P14
        List<SavingsPlanItem> afterDelP5 = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 6",
                "Item 7", "Item 8", "Item 9", "Item 10", "Item 11",
                "Item 12", "Item 13", "Item 14", "Item 15");

        assertThat(afterDelP5.get(0).getName()).isEqualTo("Item 1");
        assertThat(afterDelP5.get(0).getPriority()).isEqualTo(1);
        assertThat(afterDelP5.get(0).getMonthlyAmount()).isEqualTo(60.0f); // £10 + £50 from deleted P5!
        assertThat(afterDelP5.get(4).getName()).isEqualTo("Item 6");
        assertThat(afterDelP5.get(4).getPriority()).isEqualTo(5);
        assertThat(afterDelP5.get(4).getMonthlyAmount()).isEqualTo(60.0f);

        // 2. Delete the P1 item (Item 1, which now has £60.00) -> all of P1's value (£60) goes to P2 (Item 2, £20 + £60 = £80),
        // and Item 2 becomes the new P1!
        Long item1Id = afterDelP5.get(0).getId();
        Result delP1 = controller.archiveItem(item1Id, Helpers.fakeRequest("GET", "/binSavingsPlanItem/" + item1Id).build())
                .toCompletableFuture().get();
        assertThat(delP1.status()).isEqualTo(303);

        List<SavingsPlanItem> afterDelP1 = assertPositionsInPostgres(userId, planId,
                "Item 2", "Item 3", "Item 4", "Item 6", "Item 7",
                "Item 8", "Item 9", "Item 10", "Item 11", "Item 12",
                "Item 13", "Item 14", "Item 15");

        assertThat(afterDelP1.get(0).getName()).isEqualTo("Item 2");
        assertThat(afterDelP1.get(0).getPriority()).isEqualTo(1);
        assertThat(afterDelP1.get(0).getMonthlyAmount()).isEqualTo(80.0f); // £20 + £60 from deleted P1!

        // 3. Delete the new P1 item (Item 2, which now has £80.00) -> all of its value (£80) goes to the next P2 (Item 3, £30 + £80 = £110),
        // and Item 3 becomes the new P1!
        Long item2Id = afterDelP1.get(0).getId();
        Result delNewP1 = controller.archiveItem(item2Id, Helpers.fakeRequest("GET", "/binSavingsPlanItem/" + item2Id).build())
                .toCompletableFuture().get();
        assertThat(delNewP1.status()).isEqualTo(303);

        List<SavingsPlanItem> afterDelSecondP1 = assertPositionsInPostgres(userId, planId,
                "Item 3", "Item 4", "Item 6", "Item 7", "Item 8",
                "Item 9", "Item 10", "Item 11", "Item 12", "Item 13",
                "Item 14", "Item 15");

        assertThat(afterDelSecondP1.get(0).getName()).isEqualTo("Item 3");
        assertThat(afterDelSecondP1.get(0).getPriority()).isEqualTo(1);
        assertThat(afterDelSecondP1.get(0).getMonthlyAmount()).isEqualTo(110.0f); // £30 + £80!

        // Total pot allocation across all active items is still strictly £1200.00
        SavingsPlan currentPlan = savingsPlanRepo.getActive(userId).toCompletableFuture().get();
        assertThat(currentPlan.getTotalAllocated()).isEqualTo(1200.0f);
    }

    @Test
    public void test04_ReassignP7AsP5MakesP7BecomeP5AndOldP5BecomeP6AndSoOn() throws Exception {
        String userId = "systest_user_reassign_p7_to_p5";
        SavingsPlan plan = createFifteenItemPlanInPostgres(userId);
        Long planId = plan.getId();
        SavingsPlanController controller = buildAuthenticatedController(userId);

        List<SavingsPlanItem> initial = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 5",
                "Item 6", "Item 7", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        SavingsPlanItem item7 = initial.get(6); // P7 = Item 7
        assertThat(item7.getName()).isEqualTo("Item 7");
        assertThat(item7.getPriority()).isEqualTo(7);

        // Reassign P7 (Item 7) as P5 via the controller updateItem endpoint
        Map<String, String> formData = new HashMap<>();
        formData.put("name", item7.getName());
        formData.put("type", item7.getType().name());
        formData.put("accountId", "0");
        formData.put("targetAmount", String.valueOf(item7.getTargetAmount()));
        formData.put("monthlyAmount", String.valueOf(item7.getMonthlyAmount()));
        formData.put("priority", "5");

        Http.Request updateReq = Helpers.fakeRequest("POST", "/savingsPlan/updateItem/" + item7.getId())
                .bodyForm(formData)
                .build();
        Result updateRes = controller.updateItem(item7.getId(), updateReq).toCompletableFuture().get();
        assertThat(updateRes.status()).isEqualTo(303);

        // Assert: P7 (Item 7) is now P5, old P5 (Item 5) is now P6, old P6 (Item 6) is now P7,
        // P1..P4 and P8..P15 remain in their exact positions!
        List<SavingsPlanItem> afterReassignUp = assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 2", "Item 3", "Item 4", "Item 7",
                "Item 5", "Item 6", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");

        assertThat(afterReassignUp.get(4).getName()).isEqualTo("Item 7");
        assertThat(afterReassignUp.get(4).getPriority()).isEqualTo(5);
        assertThat(afterReassignUp.get(5).getName()).isEqualTo("Item 5");
        assertThat(afterReassignUp.get(5).getPriority()).isEqualTo(6);
        assertThat(afterReassignUp.get(6).getName()).isEqualTo("Item 6");
        assertThat(afterReassignUp.get(6).getPriority()).isEqualTo(7);

        // Also test reassigning an item downwards: reassign P2 (Item 2) to P6
        SavingsPlanItem item2 = afterReassignUp.get(1);
        Map<String, String> formDataDown = new HashMap<>();
        formDataDown.put("name", item2.getName());
        formDataDown.put("type", item2.getType().name());
        formDataDown.put("accountId", "0");
        formDataDown.put("targetAmount", String.valueOf(item2.getTargetAmount()));
        formDataDown.put("monthlyAmount", String.valueOf(item2.getMonthlyAmount()));
        formDataDown.put("priority", "6");

        Http.Request updateDownReq = Helpers.fakeRequest("POST", "/savingsPlan/updateItem/" + item2.getId())
                .bodyForm(formDataDown)
                .build();
        Result updateDownRes = controller.updateItem(item2.getId(), updateDownReq).toCompletableFuture().get();
        assertThat(updateDownRes.status()).isEqualTo(303);

        // Item 3..Item 5 shift up to P2..P5, and Item 2 lands at P6
        assertPositionsInPostgres(userId, planId,
                "Item 1", "Item 3", "Item 4", "Item 7", "Item 5",
                "Item 2", "Item 6", "Item 8", "Item 9", "Item 10",
                "Item 11", "Item 12", "Item 13", "Item 14", "Item 15");
    }

    @Test
    public void test05_PostgreSQLJsonbMigrationAndStatementConfirmationEndToEnd() throws Exception {
        // 1. Verify startup migration converted bank_transaction.raw_data from jsonb to text in real PostgreSQL
        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT data_type FROM information_schema.columns WHERE table_name = 'bank_transaction' AND column_name = 'raw_data'");
             ResultSet rs = ps.executeQuery()) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("data_type")).isEqualTo("text");
        }

        // 2. Create target account in real PostgreSQL
        String userId = "systest_statement_user";
        Account natwest = new Account();
        natwest.setUserId(userId);
        natwest.setName("NatWest Reward Platinum");
        natwest.setType(Account.AccountType.DEBIT);
        natwest.setLedgerMode(Account.LedgerMode.ABL);
        natwest.setAvailableLimit(0f);
        natwest = accountRepo.add(natwest).toCompletableFuture().get();
        Long accountId = natwest.getId();

        // 3. Ingest extracted statement via MonMon webhook route
        String webhookPayload = "{\n" +
                "  \"type\": \"statement.extracted\",\n" +
                "  \"userId\": \"" + userId + "\",\n" +
                "  \"jobId\": \"systest_job_01\",\n" +
                "  \"bank\": \"NatWest\",\n" +
                "  \"startDate\": \"2026-08-01\",\n" +
                "  \"endDate\": \"2026-08-31\",\n" +
                "  \"openingBalance\": 250.00,\n" +
                "  \"closingBalance\": 875.50,\n" +
                "  \"targetAccountId\": " + accountId + ",\n" +
                "  \"transactions\": [\n" +
                "    {\n" +
                "      \"type\": \"transaction.created\",\n" +
                "      \"data\": {\n" +
                "        \"id\": \"systest_tx_01\",\n" +
                "        \"amount\": -100000,\n" +
                "        \"created\": \"2026-08-15T09:00:00Z\",\n" +
                "        \"currency\": \"GBP\",\n" +
                "        \"description\": \"Techworks Salary\",\n" +
                "        \"category\": \"income\",\n" +
                "        \"merchant\": {\"name\": \"Employer Inc\"}\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"transaction.created\",\n" +
                "      \"data\": {\n" +
                "        \"id\": \"systest_tx_02\",\n" +
                "        \"amount\": 35000,\n" +
                "        \"created\": \"2026-08-20T14:30:00Z\",\n" +
                "        \"currency\": \"GBP\",\n" +
                "        \"description\": \"Council Tax Edinburgh\",\n" +
                "        \"category\": \"bills\",\n" +
                "        \"merchant\": {\"name\": \"Edinburgh Council\"}\n" +
                "      }\n" +
                "    },\n" +
                "    {\n" +
                "      \"type\": \"transaction.created\",\n" +
                "      \"data\": {\n" +
                "        \"id\": \"systest_tx_03\",\n" +
                "        \"amount\": 2450,\n" +
                "        \"created\": \"2026-08-25T11:15:00Z\",\n" +
                "        \"currency\": \"GBP\",\n" +
                "        \"description\": \"Sainsbury Supermarkets\",\n" +
                "        \"category\": \"groceries\",\n" +
                "        \"merchant\": {\"name\": \"Sainsbury Stores\"}\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}";

        ObjectMapper mapper = new ObjectMapper();
        JsonNode jsonBody = mapper.readTree(webhookPayload);

        Http.RequestBuilder webhookReq = Helpers.fakeRequest("POST", "/api/monzo/webhook?statement=true&userId=" + userId)
                .header("Host", "localhost:9000")
                .bodyJson(jsonBody);
        Result webhookRes = route(app, webhookReq);
        assertThat(webhookRes.status()).isEqualTo(200);

        JsonNode webhookJson = mapper.readTree(contentAsString(webhookRes));
        assertThat(webhookJson.path("status").asText()).isEqualTo("ok");
        long statementId = webhookJson.path("statementId").asLong();
        assertThat(statementId).isGreaterThan(0L);

        // 4. Confirm the statement and verify PostgreSQL persistence of transactions, raw_data, and closing balance
        Http.RequestBuilder confirmReq = Helpers.fakeRequest("POST",
                "/api/bank/statement/" + statementId + "/confirm?userId=" + userId + "&accountId=" + accountId)
                .header("Host", "localhost:9000");
        Result confirmRes = route(app, confirmReq);
        assertThat(confirmRes.status()).isEqualTo(200);

        JsonNode confirmJson = mapper.readTree(contentAsString(confirmRes));
        assertThat(confirmJson.path("status").asText()).isEqualTo("ok");

        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT count(*) FROM bank_transaction WHERE userid = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getInt(1)).isEqualTo(3);
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT raw_data FROM bank_transaction WHERE externalid = 'systest_tx_01'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getString("raw_data")).contains("Techworks Salary");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT value FROM balance WHERE userid = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                    assertThat(rs.getFloat("value")).isEqualTo(875.50f);
                }
            }
        }
    }

    @Test
    public void test06_MonzoConfidentialOAuthClientPersistenceAndTokenRefreshStateInPostgres() throws Exception {
        String userId = "systest_monzo_oauth_user";

        // 1. Save Confidential OAuth Client credentials before OAuth callback
        MonzoConnection initialCreds = new MonzoConnection();
        initialCreds.setUserId(userId);
        initialCreds.setClientId("oauth2client_00009abcdef");
        initialCreds.setClientSecret("mnzconf.secret_xyz_987654321");
        initialCreds.setRedirectUri("https://monmon.dryark.uk/bank/monzo/callback");
        monzoConnRepo.save(initialCreds).toCompletableFuture().get();

        // 2. Simulate OAuth callback saving accessToken + refreshToken (without overwriting clientId/clientSecret)
        long expiredEpoch = (System.currentTimeMillis() / 1000L) - 120L; // expired 2 mins ago
        MonzoConnection callbackConn = new MonzoConnection(
                userId,
                "access_token_v1",
                "refresh_token_v1",
                "user_monzo_001",
                expiredEpoch
        );
        monzoConnRepo.save(callbackConn).toCompletableFuture().get();

        Optional<MonzoConnection> loadedOpt = monzoConnRepo.findByUserId(userId).toCompletableFuture().get();
        assertThat(loadedOpt).isPresent();
        MonzoConnection loaded = loadedOpt.get();

        assertThat(loaded.isLongTermOAuth()).isTrue();
        assertThat(loaded.hasAccessToken()).isTrue();
        assertThat(loaded.hasRefreshToken()).isTrue();
        assertThat(loaded.isTokenExpired()).isTrue();
        assertThat(loaded.getClientId()).isEqualTo("oauth2client_00009abcdef");
        assertThat(loaded.getClientSecret()).isEqualTo("mnzconf.secret_xyz_987654321");
        assertThat(loaded.getRedirectUri()).isEqualTo("https://monmon.dryark.uk/bank/monzo/callback");

        // 3. Simulate token refresh rotating both access_token and refresh_token
        long newExpiresAt = (System.currentTimeMillis() / 1000L) + 21600L; // valid for 6 hours
        loaded.setAccessToken("access_token_v2_refreshed");
        loaded.setRefreshToken("refresh_token_v2_rotated");
        loaded.setExpiresAt(newExpiresAt);
        monzoConnRepo.save(loaded).toCompletableFuture().get();

        MonzoConnection afterRefresh = monzoConnRepo.findByUserId(userId).toCompletableFuture().get().orElseThrow();
        assertThat(afterRefresh.isTokenExpired()).isFalse();
        assertThat(afterRefresh.getAccessToken()).isEqualTo("access_token_v2_refreshed");
        assertThat(afterRefresh.getRefreshToken()).isEqualTo("refresh_token_v2_rotated");
        assertThat(afterRefresh.getClientId()).isEqualTo("oauth2client_00009abcdef");
        assertThat(afterRefresh.getClientSecret()).isEqualTo("mnzconf.secret_xyz_987654321");
    }

    @Test
    public void test07_AccountWith22OutgoingsDoesNotDuplicateItemsOrJumpPrioritiesToP23P24InPostgres() throws Exception {
        String userId = "systest_cartesian_join_regression";
        BalanceRepository balanceRepo = app.injector().instanceOf(BalanceRepository.class);

        // 1. Create a Debit Account with 22 Outgoings and 5 Balances in PostgreSQL
        // (Previously, EAGER outer joins across SavingsPlan.items -> SavingsPlanItem.account -> Account.outgoings
        // caused Hibernate to insert 22 duplicate references of Item 1 into SavingsPlan.items,
        // making Item 2 jump to P23 and Item 3 jump to P24, and breaking moveItemDown/Up!)
        Account heavyAccount = new Account();
        heavyAccount.setUserId(userId);
        heavyAccount.setName("Heavy Bills Current Account");
        heavyAccount.setType(Account.AccountType.DEBIT);
        heavyAccount.setAvailableLimit(1000f);
        heavyAccount = accountRepo.add(heavyAccount).toCompletableFuture().get();

        for (int i = 1; i <= 22; i++) {
            Outgoing o = new Outgoing();
            o.setUserId(userId);
            o.setName("Monthly Direct Debit #" + i);
            o.setCost(15.0f + i);
            o.setOutgoingDay((i % 28) + 1);
            o.setAccount(heavyAccount);
            outgoingRepo.add(o).toCompletableFuture().get();
        }

        for (int b = 1; b <= 5; b++) {
            Balance bal = new Balance();
            bal.setUserId(userId);
            bal.setAccount(heavyAccount);
            bal.setValue(-250.0f + (b * 10f));
            bal.setTimestamp((System.currentTimeMillis() / 1000L) - (b * 86400L));
            balanceRepo.add(bal).toCompletableFuture().get();
        }

        // 2. Create a SavingsPlan and link Item 1 to heavyAccount, plus Item 2 and Item 3
        SavingsPlan plan = new SavingsPlan(userId, "Regression Guard Plan", 20);
        plan = savingsPlanRepo.createOrReplace(userId, plan).toCompletableFuture().get();
        Long planId = plan.getId();

        SavingsPlanItem item1 = new SavingsPlanItem("Overdraft Clearout", SavingsPlanItem.ItemType.DEBT_PAYOFF, heavyAccount, 250f, 100f, 1);
        SavingsPlanItem item2 = new SavingsPlanItem("Barclaycard Payoff", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, 800f, 150f, 2);
        SavingsPlanItem item3 = new SavingsPlanItem("Emergency Savings", SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 2000f, 200f, 3);

        savingsPlanRepo.addItem(planId, item1).toCompletableFuture().get();
        savingsPlanRepo.addItem(planId, item2).toCompletableFuture().get();
        savingsPlanRepo.addItem(planId, item3).toCompletableFuture().get();

        // 3. Assert via getActive, listComplete, findById, and raw PostgreSQL SQL that there are strictly 3 items at P1, P2, P3
        List<SavingsPlanItem> activeItems = assertPositionsInPostgres(userId, planId,
                "Overdraft Clearout", "Barclaycard Payoff", "Emergency Savings");
        assertThat(activeItems).hasSize(3);
        assertThat(activeItems).extracting(SavingsPlanItem::getPriority).containsExactly(1, 2, 3);

        List<SavingsPlan> completeList = savingsPlanRepo.listComplete(userId).toCompletableFuture().get()
                .collect(java.util.stream.Collectors.toList());
        assertThat(completeList).hasSize(1);
        assertThat(completeList.get(0).getItems()).hasSize(3);
        assertThat(completeList.get(0).getItems()).extracting(SavingsPlanItem::getPriority).containsExactly(1, 2, 3);

        // 4. Move Item 1 (linked to the 22-outgoing account) down 1 position -> must become P2, Barclaycard becomes P1
        Long overdraftItemId = activeItems.get(0).getId();
        Boolean movedDown = savingsPlanRepo.moveItemDown(overdraftItemId).toCompletableFuture().get();
        assertThat(movedDown).isTrue();

        assertPositionsInPostgres(userId, planId,
                "Barclaycard Payoff", "Overdraft Clearout", "Emergency Savings");

        // 5. Move Item 1 to bottom -> must become P3
        Boolean movedBottom = savingsPlanRepo.moveToBottom(overdraftItemId).toCompletableFuture().get();
        assertThat(movedBottom).isTrue();

        assertPositionsInPostgres(userId, planId,
                "Barclaycard Payoff", "Emergency Savings", "Overdraft Clearout");

        // 6. Reassign Item 1 from P3 back to P1 via updateItem
        savingsPlanRepo.updateItem(
                overdraftItemId,
                "Overdraft Clearout",
                SavingsPlanItem.ItemType.DEBT_PAYOFF,
                heavyAccount.getId(),
                250f,
                100f,
                1
        ).toCompletableFuture().get();

        assertPositionsInPostgres(userId, planId,
                "Overdraft Clearout", "Barclaycard Payoff", "Emergency Savings");
    }

    @Test
    public void test08_InsertItemAtExplicitMiddlePriorityAndAppendNullPriorityInPostgres() throws Exception {
        String userId = "systest_insert_middle_and_append";
        SavingsPlan plan = new SavingsPlan(userId, "Insert & Append Plan", 25);
        plan = savingsPlanRepo.createOrReplace(userId, plan).toCompletableFuture().get();
        Long planId = plan.getId();

        for (int i = 1; i <= 6; i++) {
            SavingsPlanItem itm = new SavingsPlanItem("Goal " + i, SavingsPlanItem.ItemType.SAVINGS_TARGET, null, 500f, i * 10f, i);
            savingsPlanRepo.addItem(planId, itm).toCompletableFuture().get();
        }

        assertPositionsInPostgres(userId, planId,
                "Goal 1", "Goal 2", "Goal 3", "Goal 4", "Goal 5", "Goal 6");

        // 1. Add a new item with explicit priority = 3 -> should insert at P3 and shift Goal 3..Goal 6 to P4..P7
        SavingsPlanItem urgentP3 = new SavingsPlanItem("Urgent P3 Goal", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, 300f, 75f, 3);
        savingsPlanRepo.addItem(planId, urgentP3).toCompletableFuture().get();

        assertPositionsInPostgres(userId, planId,
                "Goal 1", "Goal 2", "Urgent P3 Goal", "Goal 3", "Goal 4", "Goal 5", "Goal 6");

        // 2. Add a new item with priority = null (as used by Add All Credit Cards / Overdrafts) -> should append at P8
        SavingsPlanItem tailGoal = new SavingsPlanItem("Appended Tail Goal", SavingsPlanItem.ItemType.DEBT_PAYOFF, null, 400f, 0f, null);
        savingsPlanRepo.addItem(planId, tailGoal).toCompletableFuture().get();

        assertPositionsInPostgres(userId, planId,
                "Goal 1", "Goal 2", "Urgent P3 Goal", "Goal 3", "Goal 4", "Goal 5", "Goal 6", "Appended Tail Goal");
    }

    @Test
    public void test09_MonzoTransactionsPopulateDigHistogramAndBankSyncStatusInPostgres() throws Exception {
        String userId = "systest_monzo_dig_and_bank_user";

        // 1. Create Monzo Debit and Monzo Flex TBL accounts in real PostgreSQL
        Account monzoDebit = new Account();
        monzoDebit.setUserId(userId);
        monzoDebit.setName("Monzo Current Account");
        monzoDebit.setType(Account.AccountType.DEBIT);
        monzoDebit.setLedgerMode(Account.LedgerMode.TBL);
        monzoDebit.setAvailableLimit(0f);
        monzoDebit = accountRepo.add(monzoDebit).toCompletableFuture().get();

        Account monzoFlex = new Account();
        monzoFlex.setUserId(userId);
        monzoFlex.setName("Monzo Flex Credit Card");
        monzoFlex.setType(Account.AccountType.CREDIT);
        monzoFlex.setLedgerMode(Account.LedgerMode.TBL);
        monzoFlex.setAvailableLimit(2000f);
        monzoFlex = accountRepo.add(monzoFlex).toCompletableFuture().get();

        // 2. Create a newly authorized OAuth MonzoConnection (with lastReceivedAt initially null)
        MonzoConnection conn = new MonzoConnection(
                userId,
                "eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCJ9.live_oauth_token",
                "monzo_refresh_token_live",
                "user_0000B8tCnLud8KMMK4JwYr",
                System.currentTimeMillis() + 21600_000L
        );
        conn.setClientId("oauth2client_0000BC0ggM7gRqWQBcKk1k");
        conn.setClientSecret("mnzconf.W4NDYFrM/v72s7LBDN8p9Gdv3gqVtDgTBND66fIym8Y=");
        conn.setWebhookDebitId("webhook_0000debit");
        conn.setWebhookFlexId("webhook_0000flex");
        conn.setWebhookUrl("https://monmon.dryark.uk/api/monzo/webhook?userId=" + userId);
        monzoConnRepo.save(conn).toCompletableFuture().get();

        // 3. Save geotagged Monzo transactions into real PostgreSQL (including Electric Tobacconist £24.99)
        BankTransactionRepository txRepo = app.injector().instanceOf(BankTransactionRepository.class);
        long nowSec = System.currentTimeMillis() / 1000L;

        BankTransaction tx1 = new BankTransaction(
                userId, monzoDebit.getId(), "tx_monzo_et_01", nowSec - 3600, "2026-10-31",
                24.99f, "Electric Tobacconist", "Electric Tobacconist", "shopping", "MONZO", false);
        tx1.setLatitude(51.7527);
        tx1.setLongitude(-0.4722);
        tx1.setMerchantAddress("Hemel Hempstead HP2 7UE, United Kingdom");
        tx1.setMerchantEmoji("🔌");

        BankTransaction tx2 = new BankTransaction(
                userId, monzoFlex.getId(), "tx_monzo_apple_02", nowSec - 7200, "2026-10-30",
                175.01f, "Apple Store", "Apple Store", "shopping", "MONZO", false);
        tx2.setLatitude(55.9533);
        tx2.setLongitude(-3.1883);
        tx2.setMerchantAddress("Princes St, Edinburgh EH2 2AN, United Kingdom");
        tx2.setMerchantEmoji("💻");

        BankTransaction tx3 = new BankTransaction(
                userId, monzoDebit.getId(), "tx_monzo_milkman_03", nowSec - 10800, "2026-10-29",
                4.20f, "The Milkman Coffee", "The Milkman Coffee", "eating_out", "MONZO", false);
        tx3.setLatitude(55.9508);
        tx3.setLongitude(-3.1895);
        tx3.setMerchantAddress("7 Cockburn St, Edinburgh EH1 1BP, United Kingdom");
        tx3.setMerchantEmoji("☕");

        BankTransaction tx4 = new BankTransaction(
                userId, monzoDebit.getId(), "tx_monzo_dishoom_04", nowSec - 14400, "2026-10-28",
                45.80f, "Dishoom Edinburgh", "Dishoom Edinburgh", "eating_out", "MONZO", false);
        tx4.setLatitude(55.9542);
        tx4.setLongitude(-3.1918);
        tx4.setMerchantAddress("3a St Andrew Square, Edinburgh EH2 2BD, United Kingdom");
        tx4.setMerchantEmoji("🍛");

        BankTransaction tx5 = new BankTransaction(
                userId, monzoDebit.getId(), "tx_monzo_waitrose_05", nowSec - 18000, "2026-10-27",
                50.00f, "Waitrose & Partners", "Waitrose & Partners", "groceries", "MONZO", false);
        tx5.setLatitude(55.9585);
        tx5.setLongitude(-3.2102);
        tx5.setMerchantAddress("Comely Bank Rd, Edinburgh EH4 1AW, United Kingdom");
        tx5.setMerchantEmoji("🛒");

        Integer savedCount = txRepo.saveAll(Arrays.asList(tx1, tx2, tx3, tx4, tx5)).toCompletableFuture().get();
        assertThat(savedCount).isEqualTo(5);

        // 4. Verify /dig PostgreSQL queries: findGeotaggedTransactions & getCategorySpendingHistogram
        List<BankTransaction> geoTxs = txRepo.findGeotaggedTransactions(userId, 2000).toCompletableFuture().get();
        assertThat(geoTxs).hasSize(5);

        List<viewModels.CategorySpend> histogram = txRepo.getCategorySpendingHistogram(userId).toCompletableFuture().get();
        assertThat(histogram).isNotEmpty();
        assertThat(histogram).hasSize(3);

        // Shopping = 24.99 + 175.01 = 200.00 (66.7%), Eating Out = 4.20 + 45.80 = 50.00 (16.7%), Groceries = 50.00 (16.7%)
        assertThat(histogram.get(0).getFormattedCategory()).isEqualTo("Shopping");
        assertThat(histogram.get(0).getCount()).isEqualTo(2L);
        assertThat(histogram.get(0).getTotalSpent()).isCloseTo(200.00, org.assertj.core.data.Offset.offset(0.05));
        assertThat(histogram.get(0).getPercentage()).isCloseTo(66.7, org.assertj.core.data.Offset.offset(0.2));

        // 5. Verify DigViewModel and rendered /dig page have non-zero Total Monzo Spent (£300.00) and Top Category "Shopping"
        viewModels.DigViewModel digVm = new viewModels.DigViewModel(geoTxs, histogram, true, false, 2000);
        assertThat(digVm.getTotalGeotagged()).isEqualTo(5);
        assertThat(digVm.getTotalSpent()).isCloseTo(300.00, org.assertj.core.data.Offset.offset(0.05));
        assertThat(digVm.getHistogram().get(0).getFormattedCategory()).isEqualTo("Shopping");

        controllers.DigController digController = app.injector().instanceOf(controllers.DigController.class);
        SessionStore mockSessionStore = mock(SessionStore.class);
        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", userId);
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(mockSessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
        when(mockSessionStore.get(any(), eq("user_access_status"))).thenReturn(Optional.of(UserAccess.AccessStatus.APPROVED));

        Field digSessionField = controllers.DigController.class.getDeclaredField("playSessionStore");
        digSessionField.setAccessible(true);
        digSessionField.set(digController, mockSessionStore);

        Http.Request digReq = Helpers.fakeRequest("GET", "/monzo-dig").build();
        Result digRes = digController.index(digReq).toCompletableFuture().get();
        assertThat(digRes.status()).isEqualTo(200);
        String digHtml = contentAsString(digRes);
        assertThat(digHtml).contains("Monzo Dig — Monzo Spending Heatmap");
        assertThat(digHtml).contains("Where You've Spent on Monzo");
        assertThat(digHtml).contains("How You've Spent on Monzo");
        assertThat(digHtml).contains("£300.00");
        assertThat(digHtml).contains("Shopping");
        assertThat(digHtml).contains("Electric Tobacconist");

        // 6. Verify /bank controller populates lastReceivedAt from existing Monzo transactions so it does NOT say "Awaiting first transaction event"
        controllers.BankSyncController bankController = app.injector().instanceOf(controllers.BankSyncController.class);
        Field bankSessionField = controllers.BankSyncController.class.getDeclaredField("playSessionStore");
        bankSessionField.setAccessible(true);
        bankSessionField.set(bankController, mockSessionStore);

        Http.Request bankReq = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("GET", "/bank")).build();
        Result bankRes = bankController.index(bankReq).toCompletableFuture().get();
        assertThat(bankRes.status()).isEqualTo(200);
        String bankHtml = contentAsString(bankRes);
        assertThat(bankHtml).contains("Last received from Monzo");
        assertThat(bankHtml).doesNotContain("Awaiting first transaction event");
    }

    @Test
    public void test10_NatWestTransactionListPdfPipelineAVsPipelineBAndPennyPerfectPostgresIngest() throws Exception {
        java.io.File pdfFile = new java.io.File("legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest.pdf");
        java.io.File gtFile = new java.io.File("legmon/test_fixtures/TXN_22102026_29072026_29102026_NatWest_ground_truth.json");
        assertThat(pdfFile).exists();
        assertThat(gtFile).exists();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode gtRoot = mapper.readTree(gtFile);
        JsonNode summary = gtRoot.path("summary");
        int expectedTxCount = summary.path("transactionCount").asInt();
        java.math.BigDecimal expectedPaidIn = new java.math.BigDecimal(summary.path("totalPaidIn").asText());
        java.math.BigDecimal expectedPaidOut = new java.math.BigDecimal(summary.path("totalPaidOut").asText());
        java.math.BigDecimal expectedNet = new java.math.BigDecimal(summary.path("net").asText());

        assertThat(expectedTxCount).isEqualTo(78);
        assertThat(expectedPaidIn).isEqualByComparingTo("10432.05");
        assertThat(expectedPaidOut).isEqualByComparingTo("10399.08");
        assertThat(expectedNet).isEqualByComparingTo("32.97");

        String userId = "systest_natwest_txn_22102026";
        Account natwest = new Account();
        natwest.setUserId(userId);
        natwest.setName("NatWest Reward Platinum 343");
        natwest.setType(Account.AccountType.DEBIT);
        natwest.setLedgerMode(Account.LedgerMode.ABL);
        natwest.setAvailableLimit(0f);
        natwest = accountRepo.add(natwest).toCompletableFuture().get();
        Long accountId = natwest.getId();

        // Verify Tier 3 Fixed-Cost Zero-LLM Deterministic Fallback (pdftotext -layout + DeterministicStatementParser, MISTRAL_API_KEY="")
        JsonNode payloadDet = runLegmonContainerPipeline("systest-legmon-det", 19010, 19011, pdfFile, userId, accountId, "", false, false);
        if (payloadDet != null) {
            java.math.BigDecimal paidInDet = new java.math.BigDecimal(payloadDet.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal paidOutDet = new java.math.BigDecimal(payloadDet.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
            java.math.BigDecimal errDet = paidInDet.subtract(expectedPaidIn).abs().add(paidOutDet.subtract(expectedPaidOut).abs());
            System.out.println(String.format(
                    "[NatWest System Test] Tier 3 Zero-LLM Deterministic Fallback: txCount=%d, paidIn=£%s, paidOut=£%s, totalError=£%s",
                    payloadDet.path("transactions").size(), paidInDet, paidOutDet, errDet));
            assertThat(payloadDet.path("transactions").size()).isEqualTo(78);
            assertThat(errDet).as("Zero-LLM deterministic fallback total error vs ground truth").isEqualByComparingTo("0.00");
        }

        // Check if MISTRAL_API_KEY and legmon:latest Docker container are available to run live A vs B LegMon pipeline comparison
        String mistralKey = resolveMistralApiKeyForSysTest();
        JsonNode pipelineBWebhookPayload = null;
        if (mistralKey != null && !mistralKey.isEmpty()) {
            // Run Pipeline A (legacy xberg native column-segmented OCR -> Mistral) and Pipeline B (Mistral Document AI /v1/ocr -> Mistral)
            JsonNode payloadA = runLegmonContainerPipeline("systest-legmon-a", 19006, 19007, pdfFile, userId, accountId, mistralKey, false, true);
            JsonNode payloadB = runLegmonContainerPipeline("systest-legmon-b", 19008, 19009, pdfFile, userId, accountId, mistralKey, true, false);

            if (payloadA != null && payloadB != null) {
                // Ingest Pipeline A webhook into MonMon & inspect accuracy in PostgreSQL
                Http.RequestBuilder reqA = Helpers.fakeRequest("POST", "/api/monzo/webhook?statement=true&userId=" + userId + "_A")
                        .header("Host", "localhost:9000")
                        .bodyJson(payloadA);
                Result resA = route(app, reqA);
                assertThat(resA.status()).isEqualTo(200);
                JsonNode jsonA = mapper.readTree(contentAsString(resA));
                java.math.BigDecimal paidInA = new java.math.BigDecimal(jsonA.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
                java.math.BigDecimal paidOutA = new java.math.BigDecimal(jsonA.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
                java.math.BigDecimal errA = paidInA.subtract(expectedPaidIn).abs().add(paidOutA.subtract(expectedPaidOut).abs());

                // Pipeline B must be penny-perfect (error == 0.00) and strictly more accurate than legacy Pipeline A
                pipelineBWebhookPayload = payloadB;
                java.math.BigDecimal paidInB = new java.math.BigDecimal(payloadB.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
                java.math.BigDecimal paidOutB = new java.math.BigDecimal(payloadB.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP);
                java.math.BigDecimal errB = paidInB.subtract(expectedPaidIn).abs().add(paidOutB.subtract(expectedPaidOut).abs());

                System.out.println(String.format(
                        "[NatWest System Test] Pipeline A (legacy xberg): txCount=%d, paidIn=£%s, paidOut=£%s, totalError=£%s",
                        payloadA.path("transactions").size(), paidInA, paidOutA, errA));
                System.out.println(String.format(
                        "[NatWest System Test] Pipeline B (Mistral Document OCR): txCount=%d, paidIn=£%s, paidOut=£%s, totalError=£%s",
                        payloadB.path("transactions").size(), paidInB, paidOutB, errB));

                assertThat(errB).as("Pipeline B total error vs ground truth").isEqualByComparingTo("0.00");
                assertThat(errB).isLessThanOrEqualTo(errA);
            }
        }

        if (pipelineBWebhookPayload == null) {
            pipelineBWebhookPayload = buildWebhookPayloadFromGroundTruth(gtRoot, userId, accountId);
        }

        // Ingest the winning Pipeline B payload into MonMon via /api/monzo/webhook?statement=true
        Http.RequestBuilder webhookReq = Helpers.fakeRequest("POST", "/api/monzo/webhook?statement=true&userId=" + userId)
                .header("Host", "localhost:9000")
                .bodyJson(pipelineBWebhookPayload);
        Result webhookRes = route(app, webhookReq);
        assertThat(webhookRes.status()).isEqualTo(200);

        JsonNode webhookJson = mapper.readTree(contentAsString(webhookRes));
        assertThat(webhookJson.path("status").asText()).isEqualTo("ok");
        long statementId = webhookJson.path("statementId").asLong();
        assertThat(statementId).isGreaterThan(0L);
        assertThat(webhookJson.path("totalPaidIn").asDouble()).isCloseTo(10432.05, org.assertj.core.data.Offset.offset(0.01));
        assertThat(webhookJson.path("totalPaidOut").asDouble()).isCloseTo(10399.08, org.assertj.core.data.Offset.offset(0.01));

        // Verify pending_statement in PostgreSQL stores total_paid_in and total_paid_out as separate figures
        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT transaction_count, total_paid_in, total_paid_out FROM pending_statement WHERE id = ?")) {
            ps.setLong(1, statementId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("transaction_count")).isEqualTo(78);
                assertThat(rs.getDouble("total_paid_in")).isCloseTo(10432.05, org.assertj.core.data.Offset.offset(0.01));
                assertThat(rs.getDouble("total_paid_out")).isCloseTo(10399.08, org.assertj.core.data.Offset.offset(0.01));
            }
        }

        // Confirm the statement with an anchor closing balance of £1,000.00 on 2026-10-22 -> opening balance must reconstruct to £1,000.00 - £32.97 = £967.03!
        Http.RequestBuilder confirmReq = Helpers.fakeRequest("POST",
                "/api/bank/statement/" + statementId + "/confirm?userId=" + userId + "&accountId=" + accountId + "&anchorBalance=1000.00&anchorDate=2026-10-22")
                .header("Host", "localhost:9000");
        Result confirmRes = route(app, confirmReq);
        assertThat(confirmRes.status()).isEqualTo(200);

        // Verify directly in PostgreSQL using SQL aggregation that all 78 transactions (10 Paid In = £10,432.05, 68 Paid Out = £10,399.08) are persisted!
        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS tx_count, " +
                     "SUM(CASE WHEN amount < 0 THEN 1 ELSE 0 END) AS paid_in_count, " +
                     "SUM(CASE WHEN amount > 0 THEN 1 ELSE 0 END) AS paid_out_count, " +
                     "ROUND(SUM(CASE WHEN amount < 0 THEN -amount::numeric ELSE 0 END), 2) AS total_paid_in, " +
                     "ROUND(SUM(CASE WHEN amount > 0 THEN amount::numeric ELSE 0 END), 2) AS total_paid_out " +
                     "FROM bank_transaction WHERE userid = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("tx_count")).isEqualTo(78);
                assertThat(rs.getInt("paid_in_count")).isEqualTo(10);
                assertThat(rs.getInt("paid_out_count")).isEqualTo(68);
                assertThat(rs.getBigDecimal("total_paid_in")).isEqualByComparingTo("10432.05");
                assertThat(rs.getBigDecimal("total_paid_out")).isEqualByComparingTo("10399.08");
            }
        }
    }

    @Test
    public void test11_LloydsAndFirstDirectStatementsPipelineBAndDeterministicFallbackInPostgres() throws Exception {
        java.io.File lloydsPdf = new java.io.File("legmon/test_fixtures/Statement_2026_10.pdf");
        java.io.File fdPdf = new java.io.File("legmon/test_fixtures/fd statement 9363 26102026.pdf");
        assertThat(lloydsPdf).exists();
        assertThat(fdPdf).exists();

        String mistralKey = resolveMistralApiKeyForSysTest();

        // 1. Lloyds Bank Statement (5 txs: 1 Paid In = £500.00, 4 Paid Out = £499.98, open = £0.00, close = £0.02)
        String lloydsUser = "systest_lloyds_2026_10";
        Account lloydsAcc = new Account();
        lloydsAcc.setUserId(lloydsUser);
        lloydsAcc.setName("Lloyds Classic 9368");
        lloydsAcc.setType(Account.AccountType.DEBIT);
        lloydsAcc.setLedgerMode(Account.LedgerMode.ABL);
        lloydsAcc.setAvailableLimit(0f);
        lloydsAcc = accountRepo.add(lloydsAcc).toCompletableFuture().get();

        JsonNode lloydsDet = runLegmonContainerPipeline("systest-lloyds-det", 19012, 19013, lloydsPdf, lloydsUser, lloydsAcc.getId(), "", false, false);
        assertThat(lloydsDet).isNotNull();
        assertThat(lloydsDet.path("transactions").size()).isEqualTo(5);
        assertThat(new java.math.BigDecimal(lloydsDet.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("500.00");
        assertThat(new java.math.BigDecimal(lloydsDet.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("499.98");

        JsonNode lloydsWebhook = lloydsDet;
        if (mistralKey != null && !mistralKey.isEmpty()) {
            JsonNode lloydsB = runLegmonContainerPipeline("systest-lloyds-b", 19014, 19015, lloydsPdf, lloydsUser, lloydsAcc.getId(), mistralKey, true, false);
            if (lloydsB != null) {
                assertThat(lloydsB.path("transactions").size()).isEqualTo(5);
                assertThat(new java.math.BigDecimal(lloydsB.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("500.00");
                assertThat(new java.math.BigDecimal(lloydsB.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("499.98");
                lloydsWebhook = lloydsB;
            }
        }

        Result lloydsWhRes = route(app, Helpers.fakeRequest("POST", "/api/monzo/webhook?statement=true&userId=" + lloydsUser)
                .header("Host", "localhost:9000").bodyJson(lloydsWebhook));
        assertThat(lloydsWhRes.status()).isEqualTo(200);
        long lloydsStmtId = new ObjectMapper().readTree(contentAsString(lloydsWhRes)).path("statementId").asLong();
        Result lloydsConfirm = route(app, Helpers.fakeRequest("POST", "/api/bank/statement/" + lloydsStmtId + "/confirm?userId=" + lloydsUser + "&accountId=" + lloydsAcc.getId())
                .header("Host", "localhost:9000"));
        assertThat(lloydsConfirm.status()).isEqualTo(200);

        // 2. First Direct Statement (18 txs: 2 Paid In = £700.00, 16 Paid Out = £652.61, open = £14.50, close = £61.89)
        String fdUser = "systest_fd_9363_26102026";
        Account fdAcc = new Account();
        fdAcc.setUserId(fdUser);
        fdAcc.setName("First Direct 1st Account 9363");
        fdAcc.setType(Account.AccountType.DEBIT);
        fdAcc.setLedgerMode(Account.LedgerMode.ABL);
        fdAcc.setAvailableLimit(0f);
        fdAcc = accountRepo.add(fdAcc).toCompletableFuture().get();

        JsonNode fdDet = runLegmonContainerPipeline("systest-fd-det", 19016, 19017, fdPdf, fdUser, fdAcc.getId(), "", false, false);
        assertThat(fdDet).isNotNull();
        assertThat(fdDet.path("transactions").size()).isEqualTo(18);
        assertThat(new java.math.BigDecimal(fdDet.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("700.00");
        assertThat(new java.math.BigDecimal(fdDet.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("652.61");

        JsonNode fdWebhook = fdDet;
        if (mistralKey != null && !mistralKey.isEmpty()) {
            JsonNode fdB = runLegmonContainerPipeline("systest-fd-b", 19018, 19019, fdPdf, fdUser, fdAcc.getId(), mistralKey, true, false);
            if (fdB != null) {
                assertThat(fdB.path("transactions").size()).isEqualTo(18);
                assertThat(new java.math.BigDecimal(fdB.path("totalPaidIn").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("700.00");
                assertThat(new java.math.BigDecimal(fdB.path("totalPaidOut").asText("0.00")).setScale(2, java.math.RoundingMode.HALF_UP)).isEqualByComparingTo("652.61");
                fdWebhook = fdB;
            }
        }

        Result fdWhRes = route(app, Helpers.fakeRequest("POST", "/api/monzo/webhook?statement=true&userId=" + fdUser)
                .header("Host", "localhost:9000").bodyJson(fdWebhook));
        assertThat(fdWhRes.status()).isEqualTo(200);
        long fdStmtId = new ObjectMapper().readTree(contentAsString(fdWhRes)).path("statementId").asLong();
        Result fdConfirm = route(app, Helpers.fakeRequest("POST", "/api/bank/statement/" + fdStmtId + "/confirm?userId=" + fdUser + "&accountId=" + fdAcc.getId())
                .header("Host", "localhost:9000"));
        assertThat(fdConfirm.status()).isEqualTo(200);

        // Verify both statements' persisted PostgreSQL rows to the penny
        try (Connection conn = DriverManager.getConnection(SYSTEST_DB_URL, PG_USER, PG_PASS);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) AS tx_count, " +
                     "SUM(CASE WHEN amount < 0 THEN 1 ELSE 0 END) AS paid_in_count, " +
                     "SUM(CASE WHEN amount > 0 THEN 1 ELSE 0 END) AS paid_out_count, " +
                     "ROUND(SUM(CASE WHEN amount < 0 THEN -amount::numeric ELSE 0 END), 2) AS total_paid_in, " +
                     "ROUND(SUM(CASE WHEN amount > 0 THEN amount::numeric ELSE 0 END), 2) AS total_paid_out " +
                     "FROM bank_transaction WHERE userid = ?")) {
            ps.setString(1, lloydsUser);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("tx_count")).isEqualTo(5);
                assertThat(rs.getInt("paid_in_count")).isEqualTo(1);
                assertThat(rs.getInt("paid_out_count")).isEqualTo(4);
                assertThat(rs.getBigDecimal("total_paid_in")).isEqualByComparingTo("500.00");
                assertThat(rs.getBigDecimal("total_paid_out")).isEqualByComparingTo("499.98");
            }
            ps.setString(1, fdUser);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("tx_count")).isEqualTo(18);
                assertThat(rs.getInt("paid_in_count")).isEqualTo(2);
                assertThat(rs.getInt("paid_out_count")).isEqualTo(16);
                assertThat(rs.getBigDecimal("total_paid_in")).isEqualByComparingTo("700.00");
                assertThat(rs.getBigDecimal("total_paid_out")).isEqualByComparingTo("652.61");
            }
        }
    }

    @Test
    public void test12_historicSpentAccountBreakdownsAndPotsInPeriod() throws Exception {
        String userId = "systest_historic_spent_parity";
        BalanceRepository balanceRepo = app.injector().instanceOf(BalanceRepository.class);
        PotRepository potRepo = app.injector().instanceOf(PotRepository.class);

        // 1. Create Debit Account and Savings Account in PostgreSQL
        Account currentAcc = new Account();
        currentAcc.setUserId(userId);
        currentAcc.setName("Parity Debit Account");
        currentAcc.setType(Account.AccountType.DEBIT);
        currentAcc.setAvailableLimit(500.0f);
        currentAcc = accountRepo.add(currentAcc).toCompletableFuture().get();

        Account savingsAcc = new Account();
        savingsAcc.setUserId(userId);
        savingsAcc.setName("Parity Savings Account");
        savingsAcc.setType(Account.AccountType.SHORT_TERM_SAVINGS);
        savingsAcc.setAvailableLimit(0.0f);
        savingsAcc = accountRepo.add(savingsAcc).toCompletableFuture().get();

        // 2. Insert balances for both accounts across pay cycle
        long nowSec = System.currentTimeMillis() / 1000L;
        long daySec = 86400L;

        // Debit: start = 1200, end = 950 -> 250 spent
        Balance b1 = new Balance();
        b1.setUserId(userId);
        b1.setAccount(currentAcc);
        b1.setValue(1200.0f);
        b1.setTimestamp(nowSec - 25 * daySec);
        balanceRepo.add(b1).toCompletableFuture().get();

        Balance b2 = new Balance();
        b2.setUserId(userId);
        b2.setAccount(currentAcc);
        b2.setValue(950.0f);
        b2.setTimestamp(nowSec - 2 * daySec);
        balanceRepo.add(b2).toCompletableFuture().get();

        // Savings: start = 3000, end = 3100 -> 100 deposit (-100 spent)
        Balance s1 = new Balance();
        s1.setUserId(userId);
        s1.setAccount(savingsAcc);
        s1.setValue(3000.0f);
        s1.setTimestamp(nowSec - 25 * daySec);
        balanceRepo.add(s1).toCompletableFuture().get();

        Balance s2 = new Balance();
        s2.setUserId(userId);
        s2.setAccount(savingsAcc);
        s2.setValue(3100.0f);
        s2.setTimestamp(nowSec - 2 * daySec);
        balanceRepo.add(s2).toCompletableFuture().get();

        // 3. Create Monthly Pot linked to Debit Account
        Pot pot = new Pot(userId, "Household Monthly Pot", Pot.PotType.MONTHLY);
        pot.accounts = new ArrayList<>(Collections.singletonList(currentAcc));
        pot.setSpendingPlanFood(200.0f);
        pot.setSpendingPlanFuel(50.0f);
        pot.setCreatedAt(nowSec - 26 * daySec);
        pot = potRepo.createOrReplace(userId, pot).toCompletableFuture().get();

        // 4. Calculate Historic Spent via HistoricSpentCalculator
        List<Account> userAccounts = accountRepo.list(userId).toCompletableFuture().get();
        List<Pot> userPots = potRepo.list(userId).toCompletableFuture().get();

        viewModels.HistoricSpentViewModel vm = helpers.HistoricSpentCalculator.calculateHistoricSpent(
                userAccounts,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                userPots,
                28,
                500.0f,
                null
        );

        assertThat(vm.getCycles()).isNotEmpty();
        viewModels.HistoricCycleSpent cycle = vm.getCycles().get(0);

        // Verify account breakdowns table matches accounts involved
        assertThat(cycle.getAccountBreakdowns()).isNotEmpty();
        assertThat(cycle.getAccountBreakdowns()).hasSize(2);

        viewModels.AccountSpentItem debitItem = cycle.getAccountBreakdowns().stream()
                .filter(a -> a.getAccountName().equals("Parity Debit Account"))
                .findFirst().orElse(null);
        assertThat(debitItem).isNotNull();
        assertThat(debitItem.getStartBalance()).isEqualTo(1200.0);
        assertThat(debitItem.getEndBalance()).isEqualTo(950.0);
        assertThat(debitItem.getAmountSpent()).isEqualTo(250.0);
        assertThat(debitItem.isInPot()).isTrue();

        viewModels.AccountSpentItem savingsItem = cycle.getAccountBreakdowns().stream()
                .filter(a -> a.getAccountName().equals("Parity Savings Account"))
                .findFirst().orElse(null);
        assertThat(savingsItem).isNotNull();
        assertThat(savingsItem.getStartBalance()).isEqualTo(3000.0);
        assertThat(savingsItem.getEndBalance()).isEqualTo(3100.0);
        assertThat(savingsItem.isInPot()).isFalse();

        // Verify cycle totals across accounts
        assertThat(cycle.getStartingTotalBalance()).isEqualTo(4200.0);
        assertThat(cycle.getEndingTotalBalance()).isEqualTo(4050.0);

        // Verify Pots in Period section
        assertThat(cycle.getPotsInPeriod()).isNotEmpty();
        viewModels.HistoricPotSnapshot potSnapshot = cycle.getPotsInPeriod().get(0);
        assertThat(potSnapshot.getPot().getName()).isEqualTo("Household Monthly Pot");
        assertThat(potSnapshot.getAccountSnapshots()).isNotEmpty();
        viewModels.HistoricPotSnapshot.AccountBalanceSnapshot accSnap = potSnapshot.getAccountSnapshots().get(0);
        assertThat(accSnap.getAccountName()).isEqualTo("Parity Debit Account");
        assertThat(accSnap.getBalanceOnDate()).isEqualTo(950.0);
    }

    private String resolveMistralApiKeyForSysTest() {
        String envKey = System.getenv("MISTRAL_API_KEY");
        if (envKey != null && !envKey.trim().isEmpty()) return envKey.trim();
        for (String path : Arrays.asList("legmon/.env", ".env")) {
            java.io.File f = new java.io.File(path);
            if (f.exists()) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(f.toPath())) {
                        if (line.startsWith("MISTRAL_API_KEY=")) {
                            String v = line.substring("MISTRAL_API_KEY=".length()).trim();
                            if (!v.isEmpty()) return v;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private JsonNode runLegmonContainerPipeline(
            String containerName,
            int legmonPort,
            int sinkPort,
            java.io.File pdfFile,
            String userId,
            Long accountId,
            String mistralKey,
            boolean mistralOcrEnabled,
            boolean forceXbergNative) {
        java.io.File outFile = null;
        Process sinkProc = null;
        try {
            new ProcessBuilder("docker", "rm", "-f", containerName).start().waitFor();
            outFile = java.io.File.createTempFile("legmon_webhook_", ".json");
            outFile.delete();

            sinkProc = new ProcessBuilder("python3", "legmon/scripts/webhook_capture.py", String.valueOf(sinkPort), outFile.getAbsolutePath())
                    .start();

            Process startContainer = new ProcessBuilder(
                    "docker", "run", "-d", "--rm", "--network", "host",
                    "--name", containerName,
                    "-e", "PORT=" + legmonPort,
                    "-e", "MISTRAL_API_KEY=" + mistralKey,
                    "-e", "MISTRAL_MODEL=mistral-small-latest",
                    "-e", "MISTRAL_OCR_ENABLED=" + mistralOcrEnabled,
                    "-e", "LEGMON_FORCE_XBERG_NATIVE=" + forceXbergNative,
                    "legmon:latest"
            ).start();
            if (startContainer.waitFor() != 0) {
                return null;
            }

            // Wait for LegMon /health
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            boolean ready = false;
            for (int i = 0; i < 30; i++) {
                try {
                    java.net.http.HttpRequest hReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create("http://127.0.0.1:" + legmonPort + "/health"))
                            .timeout(java.time.Duration.ofSeconds(2))
                            .GET().build();
                    if (client.send(hReq, java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                        ready = true;
                        break;
                    }
                } catch (Exception ignored) {}
                Thread.sleep(300);
            }
            if (!ready) return null;

            String callbackUrl = java.net.URLEncoder.encode("http://127.0.0.1:" + sinkPort + "/webhook", java.nio.charset.StandardCharsets.UTF_8);
            java.net.http.HttpRequest uploadReq = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + legmonPort + "/api/process?userId=" + userId + "&accountId=" + accountId + "&callbackUrl=" + callbackUrl))
                    .header("Content-Type", "application/pdf")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofFile(pdfFile.toPath()))
                    .build();
            client.send(uploadReq, java.net.http.HttpResponse.BodyHandlers.ofString());

            // Wait up to 90s for webhook sink to capture the output
            boolean exited = sinkProc.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (!exited || !outFile.exists() || outFile.length() == 0) {
                return null;
            }
            return new ObjectMapper().readTree(outFile);
        } catch (Exception e) {
            return null;
        } finally {
            if (sinkProc != null && sinkProc.isAlive()) sinkProc.destroyForcibly();
            try { new ProcessBuilder("docker", "rm", "-f", containerName).start().waitFor(); } catch (Exception ignored) {}
            if (outFile != null && outFile.exists()) outFile.delete();
        }
    }

    private JsonNode buildWebhookPayloadFromGroundTruth(JsonNode gtRoot, String userId, Long accountId) {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
        root.put("type", "statement.extracted");
        root.put("userId", userId);
        root.put("jobId", "systest_natwest_gt");
        root.put("bank", "NatWest");
        root.put("startDate", "2026-07-29");
        root.put("endDate", "2026-10-22");
        root.put("openingBalance", 0.0);
        root.put("closingBalance", 0.0);
        root.put("totalPaidIn", 10432.05);
        root.put("totalPaidOut", 10399.08);
        root.put("targetAccountId", accountId);
        com.fasterxml.jackson.databind.node.ArrayNode txs = root.putArray("transactions");

        java.util.Map<String, String> months = new java.util.HashMap<>();
        months.put("Jul", "07");
        months.put("Aug", "08");
        months.put("Sep", "09");
        months.put("Oct", "10");

        int idx = 0;
        for (JsonNode row : gtRoot.path("rows")) {
            String[] parts = row.path("date").asText().split(" ");
            String isoDate = "2026-" + months.getOrDefault(parts[1], "10") + "-" + parts[0];
            long monzoPence;
            if (!row.path("paidIn").isNull()) {
                monzoPence = new java.math.BigDecimal(row.path("paidIn").asText()).multiply(new java.math.BigDecimal(100)).longValue();
            } else {
                monzoPence = -new java.math.BigDecimal(row.path("paidOut").asText()).multiply(new java.math.BigDecimal(100)).longValue();
            }
            com.fasterxml.jackson.databind.node.ObjectNode tx = txs.addObject();
            tx.put("type", "transaction.created");
            com.fasterxml.jackson.databind.node.ObjectNode data = tx.putObject("data");
            data.put("id", "natwest_gt_tx_" + idx++);
            data.put("amount", monzoPence);
            data.put("created", isoDate + "T12:00:00Z");
            data.put("currency", "GBP");
            data.put("description", row.path("description").asText());
            data.put("category", row.path("type").asText());
            data.putObject("merchant").put("name", row.path("description").asText());
        }
        return root;
    }
}
