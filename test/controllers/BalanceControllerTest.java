package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.*;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.GoogleCalendarService;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class BalanceControllerTest {

    private void injectSessionStore(BalanceController controller, SessionStore sessionStore) throws Exception {
        Field f = BalanceController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, sessionStore);
    }

    private void mockUserProfile(SessionStore sessionStore, String userId) {
        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", "tester");
        profile.addAttribute("user_access_status", "APPROVED");
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
    }

    @Test
    public void testDeleteBalanceDeletesAndRedirects() throws Exception {
        FormFactory formFactory = mock(FormFactory.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        BalanceRepository balanceRepo = mock(BalanceRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        IncomingRepository incomingRepo = mock(IncomingRepository.class);
        PotRepository potRepo = mock(PotRepository.class);
        GoogleCalendarService gcal = mock(GoogleCalendarService.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-456");
        when(balanceRepo.delete(eq(99L), eq("u-456"))).thenReturn(CompletableFuture.completedFuture(true));

        BalanceController controller = new BalanceController(
                formFactory, messagesApi, balanceRepo, accountRepo, incomingRepo, potRepo, ec, gcal);
        injectSessionStore(controller, sessionStore);

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("POST", "/balance/99/delete")).build();
        Result result = controller.deleteBalance(99L, request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).contains("/myBalances");
        verify(balanceRepo, times(1)).delete(eq(99L), eq("u-456"));
    }

    @Test
    public void testGetErraticBalancesReturnsJsonArray() throws Exception {
        FormFactory formFactory = mock(FormFactory.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        BalanceRepository balanceRepo = mock(BalanceRepository.class);
        AccountRepository accountRepo = mock(AccountRepository.class);
        IncomingRepository incomingRepo = mock(IncomingRepository.class);
        PotRepository potRepo = mock(PotRepository.class);
        GoogleCalendarService gcal = mock(GoogleCalendarService.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-456");

        ErraticBalance eb = new ErraticBalance(101L, 5L, "Monzo Current", 1700000000L, 50000.0f, 500.0f, 1699900000L, 49500.0f);
        when(balanceRepo.findErraticBalances(eq("u-456"), anyDouble()))
                .thenReturn(CompletableFuture.completedFuture(Collections.singletonList(eb)));

        BalanceController controller = new BalanceController(
                formFactory, messagesApi, balanceRepo, accountRepo, incomingRepo, potRepo, ec, gcal);
        injectSessionStore(controller, sessionStore);

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("GET", "/api/balances/erratic?threshold=1000")).build();
        Result result = controller.getErraticBalances(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        JsonNode json = play.libs.Json.parse(body);
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(1);
        assertThat(json.get(0).get("id").asLong()).isEqualTo(101L);
        assertThat(json.get(0).get("accountName").asText()).isEqualTo("Monzo Current");
        assertThat(json.get(0).get("jumpAmount").asDouble()).isEqualTo(49500.0);
    }
}
