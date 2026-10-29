package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.SearchRepository;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import viewModels.SearchResult;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class SearchControllerTest {

    private void injectSessionStore(SearchController controller, SessionStore sessionStore) throws Exception {
        Field f = SearchController.class.getDeclaredField("playSessionStore");
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
    public void testSearchGuestOrUnauthenticatedReturnsEmptyList() throws Exception {
        SearchRepository searchRepo = mock(SearchRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.empty());

        SearchController controller = new SearchController(searchRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/search?q=test").build();
        Result result = controller.search("test", request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        JsonNode json = play.libs.Json.parse(contentAsString(result));
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(0);
        verify(searchRepo, never()).search(any(), any(), anyInt());
    }

    @Test
    public void testSearchBlankQueryReturnsEmptyListWithoutCallingRepo() throws Exception {
        SearchRepository searchRepo = mock(SearchRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);
        mockUserProfile(sessionStore, "user_search_test");

        SearchController controller = new SearchController(searchRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/search?q=").build();
        Result result = controller.search("   ", request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        JsonNode json = play.libs.Json.parse(contentAsString(result));
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(0);
        verify(searchRepo, never()).search(any(), any(), anyInt());
    }

    @Test
    public void testSearchAuthenticatedReturnsMatchingResults() throws Exception {
        SearchRepository searchRepo = mock(SearchRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);
        mockUserProfile(sessionStore, "user_search_test");

        List<SearchResult> mockHits = Arrays.asList(
                new SearchResult("account", "101", "NatWest Current", "Debit (£1,250.00)", "/myAccounts?highlight=account-101#account-101", "account-101", "fa-university", "Account"),
                new SearchResult("outgoing", "202", "Council Tax", "£145.00 • Day 1", "/myOutgoings?highlight=outgoing-202#outgoing-202", "outgoing-202", "fa-credit-card", "Outgoing")
        );

        when(searchRepo.search(eq("user_search_test"), eq("natwest"), anyInt()))
                .thenReturn(CompletableFuture.completedFuture(mockHits));

        SearchController controller = new SearchController(searchRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/api/search?q=natwest").build();
        Result result = controller.search("natwest", request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        JsonNode json = play.libs.Json.parse(contentAsString(result));
        assertThat(json.isArray()).isTrue();
        assertThat(json.size()).isEqualTo(2);

        assertThat(json.get(0).get("title").asText()).isEqualTo("NatWest Current");
        assertThat(json.get(0).get("entityType").asText()).isEqualTo("account");
        assertThat(json.get(0).get("targetUrl").asText()).isEqualTo("/myAccounts?highlight=account-101#account-101");
        assertThat(json.get(0).get("domId").asText()).isEqualTo("account-101");
        assertThat(json.get(0).get("badge").asText()).isEqualTo("Account");

        assertThat(json.get(1).get("title").asText()).isEqualTo("Council Tax");
        assertThat(json.get(1).get("entityType").asText()).isEqualTo("outgoing");
        assertThat(json.get(1).get("targetUrl").asText()).isEqualTo("/myOutgoings?highlight=outgoing-202#outgoing-202");
        assertThat(json.get(1).get("domId").asText()).isEqualTo("outgoing-202");
    }
}
