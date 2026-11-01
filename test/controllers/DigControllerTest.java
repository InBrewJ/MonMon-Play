package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.MonzoConnection;
import models.MonzoConnectionRepository;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.i18n.Messages;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import viewModels.CategorySpend;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class DigControllerTest {

    private void injectSessionStore(DigController controller, SessionStore sessionStore) throws Exception {
        Field f = DigController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, sessionStore);
    }

    private void mockUserProfile(SessionStore sessionStore, String userId) {
        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", "jason");
        profile.addAttribute("email", "jason@example.com");
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
    }

    @Test
    public void testDigIndexRendersWithHeatmapAndHistogram() throws Exception {
        BankTransactionRepository txRepo = mock(BankTransactionRepository.class);
        MonzoConnectionRepository connRepo = mock(MonzoConnectionRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        Messages messages = mock(Messages.class);
        when(messagesApi.preferred(any(Http.Request.class))).thenReturn(messages);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "user1");

        // Prepare mock geotagged transactions
        BankTransaction tx1 = new BankTransaction("user1", 101L, "ext-1", 1792454400L, "2026-10-28", 25.50f, "Tesco Covent Garden", "Tesco", "Groceries", "MONZO", false);
        tx1.setId(1L);
        tx1.setLatitude(51.5135);
        tx1.setLongitude(-0.1235);
        tx1.setMerchantAddress("Covent Garden, London");
        tx1.setMerchantEmoji("🛒");

        BankTransaction tx2 = new BankTransaction("user1", 101L, "ext-2", 1792454400L, "2026-10-28", 12.00f, "Pret A Manger", "Pret", "Eating_out", "MONZO", false);
        tx2.setId(2L);
        tx2.setLatitude(51.5154);
        tx2.setLongitude(-0.0921);
        tx2.setMerchantAddress("Cheapside, London");
        tx2.setMerchantEmoji("☕");

        when(txRepo.findGeotaggedTransactions(eq("user1"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(Arrays.asList(tx1, tx2)));

        List<CategorySpend> histogram = Arrays.asList(
                new CategorySpend("Groceries", 1L, 25.50, 25.50, 68.0),
                new CategorySpend("Eating_out", 1L, 12.00, 12.00, 32.0)
        );
        when(txRepo.getCategorySpendingHistogram(eq("user1")))
                .thenReturn(CompletableFuture.completedFuture(histogram));

        MonzoConnection conn = new MonzoConnection("user1", "tok", "ref", "monzo_u1", 999999L);
        when(connRepo.findByUserId(eq("user1")))
                .thenReturn(CompletableFuture.completedFuture(Optional.of(conn)));

        DigController controller = new DigController(txRepo, connRepo, messagesApi, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/monzo-dig").build();
        Result result = controller.index(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("Monzo Dig — Monzo Spending Heatmap");
        assertThat(body).contains("Where You've Spent on Monzo");
        assertThat(body).contains("How You've Spent on Monzo");
        assertThat(body).contains("Monzo Connected");
        assertThat(body).contains("Tesco");
        assertThat(body).contains("Groceries");
        assertThat(body).contains("Spending Time Slice");
        assertThat(body).contains("This Month");
        assertThat(body).contains("Last 2 Pay Cycles");
        assertThat(body).contains("Last 6 Pay Cycles");
        assertThat(body).contains("btn-slice-this-month");
        assertThat(body).contains("btn-slice-last-2");
        assertThat(body).contains("btn-slice-last-6");
        assertThat(body).contains("btn-slice-all");
        assertThat(body).contains("\"cycles\"");
        assertThat(body).contains("\"thisMonth\"");
        assertThat(body).contains("\"last2Cycles\"");
        assertThat(body).contains("\"last6Cycles\"");
        assertThat(body).contains("leaflet-heat.js");
        assertThat(body).contains("dig.js");
    }

    @Test
    public void testDigEmptyStateWhenNoDataFound() throws Exception {
        BankTransactionRepository txRepo = mock(BankTransactionRepository.class);
        MonzoConnectionRepository connRepo = mock(MonzoConnectionRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        Messages messages = mock(Messages.class);
        when(messagesApi.preferred(any(Http.Request.class))).thenReturn(messages);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "user_empty");

        when(txRepo.findGeotaggedTransactions(eq("user_empty"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(txRepo.getCategorySpendingHistogram(eq("user_empty")))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(connRepo.findByUserId(eq("user_empty")))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        DigController controller = new DigController(txRepo, connRepo, messagesApi, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/dig").build();
        Result result = controller.index(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("No Geotagged Monzo Transactions Found");
        assertThat(body).contains("Open Bank Sync");
    }

    @Test
    public void testDigApiDataReturnsJson() throws Exception {
        BankTransactionRepository txRepo = mock(BankTransactionRepository.class);
        MonzoConnectionRepository connRepo = mock(MonzoConnectionRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "user_api");

        BankTransaction tx = new BankTransaction("user_api", 101L, "ext-10", 1792454400L, "2026-10-28", 45.00f, "Shell", "Shell Petrol", "Fuel", "MONZO", false);
        tx.setId(10L);
        tx.setLatitude(51.4880);
        tx.setLongitude(-0.1200);
        tx.setMerchantAddress("Vauxhall, London");
        tx.setMerchantEmoji("⛽");

        when(txRepo.findGeotaggedTransactions(eq("user_api"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(Collections.singletonList(tx)));

        List<CategorySpend> histogram = Collections.singletonList(
                new CategorySpend("Fuel", 1L, 45.00, 45.00, 100.0)
        );
        when(txRepo.getCategorySpendingHistogram(eq("user_api")))
                .thenReturn(CompletableFuture.completedFuture(histogram));

        DigController controller = new DigController(txRepo, connRepo, messagesApi, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/dig/data").build();
        Result result = controller.apiData(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        assertThat(result.contentType()).contains("application/json");

        String body = contentAsString(result);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(body);

        assertThat(json.has("transactions")).isTrue();
        assertThat(json.get("transactions").size()).isEqualTo(1);
        assertThat(json.get("transactions").get(0).get("merchant").asText()).isEqualTo("Shell Petrol");
        assertThat(json.get("transactions").get(0).get("lat").asDouble()).isEqualTo(51.4880);
        assertThat(json.get("transactions").get(0).get("lng").asDouble()).isEqualTo(-0.1200);

        assertThat(json.has("histogram")).isTrue();
        assertThat(json.get("histogram").get(0).get("category").asText()).isEqualTo("Fuel");
        assertThat(json.get("histogram").get(0).get("total").asDouble()).isEqualTo(45.00);
        assertThat(json.get("limit").asInt()).isEqualTo(2000);

        assertThat(json.has("cycles")).isTrue();
        assertThat(json.get("cycles").has("thisMonth")).isTrue();
        assertThat(json.get("cycles").has("last2Cycles")).isTrue();
        assertThat(json.get("cycles").has("last6Cycles")).isTrue();
        assertThat(json.get("cycles").get("thisMonth").has("startDate")).isTrue();
        assertThat(json.get("cycles").get("thisMonth").has("endDate")).isTrue();
    }

    @Test
    public void testDigTimeSlicingWithConfiguredPayday() throws Exception {
        BankTransactionRepository txRepo = mock(BankTransactionRepository.class);
        MonzoConnectionRepository connRepo = mock(MonzoConnectionRepository.class);
        models.IncomingRepository incomingRepo = mock(models.IncomingRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        Messages messages = mock(Messages.class);
        when(messagesApi.preferred(any(Http.Request.class))).thenReturn(messages);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "user_payday25");
        when(incomingRepo.getNextPayDay(eq("user_payday25"))).thenReturn(25);

        when(txRepo.findGeotaggedTransactions(eq("user_payday25"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(txRepo.getCategorySpendingHistogram(eq("user_payday25")))
                .thenReturn(CompletableFuture.completedFuture(Collections.emptyList()));
        when(connRepo.findByUserId(eq("user_payday25")))
                .thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        DigController controller = new DigController(txRepo, connRepo, incomingRepo, messagesApi, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/monzo-dig/data").build();
        Result result = controller.apiData(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        ObjectMapper mapper = new ObjectMapper();
        JsonNode json = mapper.readTree(body);

        assertThat(json.has("cycles")).isTrue();
        assertThat(json.get("cycles").get("nominalPayday").asInt()).isEqualTo(25);
        String thisMonthStart = json.get("cycles").get("thisMonth").get("startDate").asText();
        String last2Start = json.get("cycles").get("last2Cycles").get("startDate").asText();
        String last6Start = json.get("cycles").get("last6Cycles").get("startDate").asText();

        // 6 cycles start date should be earlier than 2 cycles, which is earlier than this month start
        assertThat(last6Start.compareTo(last2Start)).isLessThan(0);
        assertThat(last2Start.compareTo(thisMonthStart)).isLessThan(0);
    }
}
