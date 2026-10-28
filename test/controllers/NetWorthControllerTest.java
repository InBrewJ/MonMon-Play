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
        pb.setTimestamp(System.currentTimeMillis());
        pension.balances = List.of(pb);

        NetWorthAsset car = new NetWorthAsset("u-123", "2018 Ford Focus", NetWorthAsset.AssetCategory.VEHICLE, 9000.0, "Daily driver");
        car.setId(1L);

        when(accountRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(pension)));
        when(assetRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.of(car)));
        when(incomingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(outgoingRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(planRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));
        when(potRepo.list("u-123")).thenReturn(CompletableFuture.completedFuture(Stream.empty()));

        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
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

        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
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

        NetWorthController controller = new NetWorthController(
                accountRepo, assetRepo, incomingRepo, outgoingRepo, planRepo, potRepo,
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
}
