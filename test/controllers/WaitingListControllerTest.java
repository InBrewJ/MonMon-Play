package controllers;

import models.UserAccess;
import models.UserAccessRepository;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.i18n.MessagesApi;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class WaitingListControllerTest {

    @Test
    public void testApprovedUserRedirectsToSpog() {
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);

        UserAccess approvedUser = new UserAccess("user-1", "alice", "alice@example.com", UserAccess.AccessStatus.APPROVED);
        when(repo.getOrCreate(eq("user-1"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(approvedUser));

        WaitingListController controller = new WaitingListController(sessionStore, repo, messagesApi);

        CommonProfile profile = new CommonProfile();
        profile.setId("user-1");
        profile.addAttribute("preferred_username", "alice");
        profile.addAttribute("email", "alice@example.com");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.Request request = Helpers.fakeRequest("GET", "/waitinglist").build();

        Result result = controller.index(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).contains("/spog");
    }

    @Test
    public void testPendingUserRendersWaitingListPage() {
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);
        MessagesApi messagesApi = mock(MessagesApi.class);
        when(messagesApi.preferred(any(Http.Request.class))).thenReturn(mock(play.i18n.Messages.class));

        UserAccess pendingUser = new UserAccess("user-2", "bob", "bob@example.com", UserAccess.AccessStatus.PENDING);
        when(repo.getOrCreate(eq("user-2"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(pendingUser));

        WaitingListController controller = new WaitingListController(sessionStore, repo, messagesApi);

        CommonProfile profile = new CommonProfile();
        profile.setId("user-2");
        profile.addAttribute("preferred_username", "bob");
        profile.addAttribute("email", "bob@example.com");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.Request request = Helpers.fakeRequest("GET", "/waitinglist").build();

        Result result = controller.index(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        String html = contentAsString(result);
        assertThat(html).contains("You're on the Waiting List");
        assertThat(html).contains("Pending Administrator Approval");
        assertThat(html).contains("bob");
    }

    @Test
    public void testUserAccessEntityMethods() {
        UserAccess access = new UserAccess("user-3", "charlie", "charlie@example.com", UserAccess.AccessStatus.PENDING);
        assertThat(access.isPending()).isTrue();
        assertThat(access.isApproved()).isFalse();
        assertThat(access.isRejected()).isFalse();
        assertThat(access.getFormattedCreatedAt()).isNotEqualTo("—");

        access.setStatus(UserAccess.AccessStatus.APPROVED);
        assertThat(access.isApproved()).isTrue();
        assertThat(access.isPending()).isFalse();

        access.setStatus(UserAccess.AccessStatus.REJECTED);
        assertThat(access.isRejected()).isTrue();
        assertThat(access.isApproved()).isFalse();
    }
}
