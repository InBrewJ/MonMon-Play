package filters;

import akka.stream.Materializer;
import models.UserAccess;
import models.UserAccessRepository;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import play.test.Helpers;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class AllowListFilterTest {

    @Test
    public void testWhitelistedRoutesPassThrough() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/").build();
        Result expected = Results.ok("landing");

        Result result = filter.apply(req -> CompletableFuture.completedFuture(expected), rh)
                .toCompletableFuture().join();

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(repo);
    }

    @Test
    public void testWaitingListRoutePassesThrough() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/waitinglist").build();
        Result expected = Results.ok("waiting list view");

        Result result = filter.apply(req -> CompletableFuture.completedFuture(expected), rh)
                .toCompletableFuture().join();

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(repo);
    }

    @Test
    public void testUnauthenticatedProtectedRequestPassesThroughToSecure() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/spog").build();
        Result expected = Results.redirect("/oidc/login");

        Result result = filter.apply(req -> CompletableFuture.completedFuture(expected), rh)
                .toCompletableFuture().join();

        assertThat(result).isSameAs(expected);
        verifyNoInteractions(repo);
    }

    @Test
    public void testPendingUserRedirectsToWaitingList() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        UserAccess pendingUser = new UserAccess("user-pending", "newguy", "new@monmon.com", UserAccess.AccessStatus.PENDING);
        when(repo.getOrCreate(eq("user-pending"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(pendingUser));

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("user-pending");
        profile.addAttribute("preferred_username", "newguy");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/spog").build();

        Result result = filter.apply(req -> CompletableFuture.completedFuture(Results.ok("spog")), rh)
                .toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).contains("/waitinglist");
    }

    @Test
    public void testPendingUserCallingApiReturns403Forbidden() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        UserAccess pendingUser = new UserAccess("user-pending", "newguy", "new@monmon.com", UserAccess.AccessStatus.PENDING);
        when(repo.getOrCreate(eq("user-pending"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(pendingUser));

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("user-pending");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/api/ed-says").build();

        Result result = filter.apply(req -> CompletableFuture.completedFuture(Results.ok("ed says")), rh)
                .toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(403);
        assertThat(Helpers.contentAsString(result)).contains("Waiting list");
    }

    @Test
    public void testApprovedUserProceedsNormally() {
        Materializer mat = mock(Materializer.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        UserAccess approvedUser = new UserAccess("user-approved", "boss", "boss@monmon.com", UserAccess.AccessStatus.APPROVED);
        when(repo.getOrCreate(eq("user-approved"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(approvedUser));

        AllowListFilter filter = new AllowListFilter(mat, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("user-approved");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.RequestHeader rh = Helpers.fakeRequest("GET", "/spog").build();

        Result expected = Results.ok("spog content");

        Result result = filter.apply(req -> CompletableFuture.completedFuture(expected), rh)
                .toCompletableFuture().join();

        assertThat(result).isSameAs(expected);
    }
}
