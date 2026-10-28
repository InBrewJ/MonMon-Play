package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.MistralService;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class NetWorthControllerTest {

    private void injectSessionStore(NetWorthController controller, SessionStore sessionStore) throws Exception {
        Field f = NetWorthController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, sessionStore);
    }

    private void mockUserProfile(SessionStore sessionStore, String userId) {
        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", "tester");
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
    }

    @Test
    public void testNetWorthIndexRendersWithAssetsAndPensions() throws Exception {
        AccountRepository accountRepo = mock(AccountRepository.class);
        NetWorthAssetRepository assetRepo = mock(NetWorthAssetRepository.class);
        BalanceRepository balanceRepo = mock(BalanceRepository.class);
        IncomingRepository incomingRepo = mock(IncomingRepository.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        PlanRepository planRepo = mock(PlanRepository.class);
        PotRepository potRepo = mock(PotRepository.class);
        MistralService mistralService = mock(MistralService.class);
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");

        Account pension = new Account();
        pension.setId(10L);
        pension.setName("Nest Pension");
        pension.setType(Account.AccountType.PENSION);
        Balance pb = new Balance();
        pb.setValue(45000f);
        pb.setTimestamp(System.currentTimeMillis() / 1000L);
        pension.balances = List.of(pb);

        NetWorthAsset car = new NetWorthAsset("u-123", "2018 Ford Focus", NetWorthAsset.AssetCategory.VEHICLE, 9000.0, "Daily driver");
        car.setId(1L);

        when(accountRepo.listComplete("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(pension)));
        when(accountRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(pension)));
        when(assetRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(car)));
        when(assetRepo.listAll("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(car)));
        when(balanceRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(incomingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(outgoingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(planRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(potRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));

        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, balanceRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
                mistralService, formFactory, ec
        );
        injectSessionStore(controller, sessionStore);

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("GET", "/netWorth")).build();
        Result result = controller.index(request);

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("Net Worth Tracker");
        assertThat(body).contains("£54000.0"); // 45k pension + 9k car
        assertThat(body).contains("Nest Pension");
        assertThat(body).contains("2018 Ford Focus");
        assertThat(body).contains("Ed Says — Retirement & Independence Horizon");
        assertThat(body).contains("Net Worth Trajectory");
        assertThat(body).contains("btn-nw-filter");
        assertThat(body).contains("networth-chart");
    }

    @Test
    public void testAddAssetSavesAndRedirects() throws Exception {
        AccountRepository accountRepo = mock(AccountRepository.class);
        NetWorthAssetRepository assetRepo = mock(NetWorthAssetRepository.class);
        IncomingRepository incomingRepo = mock(IncomingRepository.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        PlanRepository planRepo = mock(PlanRepository.class);
        PotRepository potRepo = mock(PotRepository.class);
        MistralService mistralService = mock(MistralService.class);
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");

        DynamicForm mockForm = mock(DynamicForm.class);
        when(mockForm.get("name")).thenReturn("London Flat");
        when(mockForm.get("category")).thenReturn("PROPERTY");
        when(mockForm.get("value")).thenReturn("350000");
        when(mockForm.get("notes")).thenReturn("Zoopla");
        when(formFactory.form()).thenReturn(mockForm);
        when(mockForm.bindFromRequest(any(Http.Request.class))).thenReturn(mockForm);

        NetWorthAsset savedAsset = new NetWorthAsset("u-123", "London Flat", NetWorthAsset.AssetCategory.PROPERTY, 350000.0, "Zoopla");
        savedAsset.setId(5L);
        when(assetRepo.add(any(NetWorthAsset.class))).thenReturn(CompletableFuture.completedFuture(savedAsset));

        BalanceRepository balanceRepo = mock(BalanceRepository.class);
        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, balanceRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
                mistralService, formFactory, ec
        );
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("POST", "/netWorth/addAsset").build();
        Result result = controller.addAsset(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).hasValue("/netWorth");
        verify(assetRepo, times(1)).add(any(NetWorthAsset.class));
    }

    @Test
    public void testEdSaysNetWorthReturnsJsonResponse() throws Exception {
        AccountRepository accountRepo = mock(AccountRepository.class);
        NetWorthAssetRepository assetRepo = mock(NetWorthAssetRepository.class);
        IncomingRepository incomingRepo = mock(IncomingRepository.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        PlanRepository planRepo = mock(PlanRepository.class);
        PotRepository potRepo = mock(PotRepository.class);
        MistralService mistralService = mock(MistralService.class);
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");

        when(accountRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(assetRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(incomingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(outgoingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(planRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(potRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));

        when(mistralService.isConfigured()).thenReturn(true);
        when(mistralService.askEdNetWorth(eq("u-123"), anyString(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture("You have enough bread to survive the winter."));

        BalanceRepository balanceRepo = mock(BalanceRepository.class);
        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, balanceRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
                mistralService, formFactory, ec
        );
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/ed-says-networth").build();
        Result result = controller.edSaysNetWorth(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        String jsonStr = contentAsString(result);
        JsonNode json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(jsonStr);
        assertThat(json.path("edSays").asText()).isEqualTo("You have enough bread to survive the winter.");
        assertThat(json.path("configured").asBoolean()).isTrue();
    }

    private void injectSessionStore(OutgoingController controller, SessionStore sessionStore) throws Exception {
        Field f = OutgoingController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, sessionStore);
    }

    @Test
    public void testPauseOutgoingSavesReasonAndRedirects() throws Exception {
        FormFactory formFactory = mock(FormFactory.class);
        play.i18n.MessagesApi messagesApi = mock(play.i18n.MessagesApi.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");

        Outgoing outgoing = new Outgoing();
        outgoing.setId(10L);
        outgoing.setUserId("u-123");
        outgoing.setName("Gym Membership");
        outgoing.setCost(40.0f);

        when(outgoingRepo.findById(10)).thenReturn(CompletableFuture.completedFuture(outgoing));

        Outgoing pausedOutgoing = new Outgoing();
        pausedOutgoing.setId(10L);
        pausedOutgoing.setUserId("u-123");
        pausedOutgoing.setName("Gym Membership");
        pausedOutgoing.setCost(40.0f);
        pausedOutgoing.setPaused(true);
        pausedOutgoing.setPauseReason("Winter pause");
        when(outgoingRepo.pause(10, "Winter pause")).thenReturn(CompletableFuture.completedFuture(pausedOutgoing));

        DynamicForm mockForm = mock(DynamicForm.class);
        when(mockForm.get("reason")).thenReturn("Winter pause");
        when(formFactory.form()).thenReturn(mockForm);
        when(mockForm.bindFromRequest(any(Http.Request.class))).thenReturn(mockForm);

        when(sessionStore.get(any(), eq("user_access_status"))).thenReturn(Optional.of(models.UserAccess.AccessStatus.APPROVED));

        OutgoingController controller = new OutgoingController(formFactory, messagesApi, outgoingRepo, accountRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("POST", "/pauseOutgoing/10").build();
        Result result = controller.pauseOutgoing(10, request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).hasValue("/myOutgoings");
        verify(outgoingRepo, times(1)).pause(10, "Winter pause");
    }

    @Test
    public void testUnpauseOutgoingRestoresAndRedirects() throws Exception {
        FormFactory formFactory = mock(FormFactory.class);
        play.i18n.MessagesApi messagesApi = mock(play.i18n.MessagesApi.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");

        Outgoing outgoing = new Outgoing();
        outgoing.setId(10L);
        outgoing.setUserId("u-123");
        outgoing.setName("Gym Membership");
        outgoing.setCost(40.0f);
        outgoing.setPaused(true);
        outgoing.setPauseReason("Winter pause");

        when(outgoingRepo.findById(10)).thenReturn(CompletableFuture.completedFuture(outgoing));

        Outgoing unpausedOutgoing = new Outgoing();
        unpausedOutgoing.setId(10L);
        unpausedOutgoing.setUserId("u-123");
        unpausedOutgoing.setName("Gym Membership");
        unpausedOutgoing.setCost(40.0f);
        unpausedOutgoing.setPaused(false);
        when(outgoingRepo.unpause(10)).thenReturn(CompletableFuture.completedFuture(unpausedOutgoing));

        when(sessionStore.get(any(), eq("user_access_status"))).thenReturn(Optional.of(models.UserAccess.AccessStatus.APPROVED));

        OutgoingController controller = new OutgoingController(formFactory, messagesApi, outgoingRepo, accountRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("POST", "/unpauseOutgoing/10").build();
        Result result = controller.unpauseOutgoing(10, request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).hasValue("/myOutgoings");
        verify(outgoingRepo, times(1)).unpause(10);
    }

    @Test
    public void testEdSaysOutgoingsReturnsPersuasiveAdvice() throws Exception {
        FormFactory formFactory = mock(FormFactory.class);
        play.i18n.MessagesApi messagesApi = mock(play.i18n.MessagesApi.class);
        OutgoingRepository outgoingRepo = mock(OutgoingRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        models.IncomingRepository incomingRepo = mock(models.IncomingRepository.class);
        models.PlanRepository planRepo = mock(models.PlanRepository.class);
        services.MistralService mistralService = mock(services.MistralService.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-123");
        when(sessionStore.get(any(), eq("user_access_status"))).thenReturn(Optional.of(models.UserAccess.AccessStatus.APPROVED));

        Outgoing netflix = new Outgoing();
        netflix.setId(1L);
        netflix.setUserId("u-123");
        netflix.setName("Netflix 4K");
        netflix.setCost(17.99f);
        netflix.setOutgoingDay(15);

        when(incomingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(java.util.stream.Stream.empty()));
        when(outgoingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(java.util.stream.Stream.of(netflix)));
        when(accountRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(java.util.stream.Stream.empty()));
        when(planRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(java.util.stream.Stream.empty()));

        when(mistralService.isConfigured()).thenReturn(true);
        when(mistralService.askEdOutgoings(eq("u-123"), any(), eq(false)))
                .thenReturn(CompletableFuture.completedFuture("Pause Netflix 4K immediately. You are leaking £17.99 into the sand."));

        OutgoingController controller = new OutgoingController(
                formFactory, messagesApi, outgoingRepo, accountRepo, incomingRepo, planRepo, mistralService, ec
        );
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/ed-says-outgoings").build();
        Result result = controller.edSaysOutgoings(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        String body = Helpers.contentAsString(result);
        assertThat(body).contains("Pause Netflix 4K immediately");
        assertThat(body).contains("\"configured\":true");
    }
}
