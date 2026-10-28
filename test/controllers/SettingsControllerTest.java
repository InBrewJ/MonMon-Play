package controllers;

import models.UserAccess;
import models.UserAccessRepository;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class SettingsControllerTest {

    @Test
    public void testSettingsIndexRendersWaitingList() {
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        MessagesApi messagesApi = mock(MessagesApi.class);
        when(messagesApi.preferred(any(Http.Request.class))).thenReturn(mock(play.i18n.Messages.class));
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        UserAccess admin = new UserAccess("admin-1", "admin", "admin@monmon.com", UserAccess.AccessStatus.APPROVED);
        UserAccess pendingUser = new UserAccess("newbie-1", "newbie", "newbie@monmon.com", UserAccess.AccessStatus.PENDING);
        pendingUser.setId(99L);

        when(repo.getOrCreate(eq("admin-1"), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(admin));
        when(repo.listPending())
                .thenReturn(CompletableFuture.completedFuture(List.of(pendingUser)));
        when(repo.listApproved())
                .thenReturn(CompletableFuture.completedFuture(List.of(admin)));

        SettingsController controller = new SettingsController(formFactory, ec, messagesApi, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("admin-1");
        profile.addAttribute("preferred_username", "admin");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("GET", "/settings")).build();

        Result result = controller.index(request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(200);
        String html = Helpers.contentAsString(result);
        assertThat(html).contains("User Waiting List & Approvals");
        assertThat(html).contains("1 pending approval");
        assertThat(html).contains("newbie");
        assertThat(html).contains("Approve");
    }

    @Test
    public void testApproveUserUpdatesStatusAndRedirects() {
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        MessagesApi messagesApi = mock(MessagesApi.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        when(repo.isUserApproved("admin-1")).thenReturn(CompletableFuture.completedFuture(true));
        UserAccess approvedUser = new UserAccess("newbie-1", "newbie", "newbie@monmon.com", UserAccess.AccessStatus.APPROVED);
        approvedUser.setId(99L);
        when(repo.approve(99L, "admin")).thenReturn(CompletableFuture.completedFuture(approvedUser));

        SettingsController controller = new SettingsController(formFactory, ec, messagesApi, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("admin-1");
        profile.addAttribute("preferred_username", "admin");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.Request request = Helpers.fakeRequest("POST", "/settings/approveUser/99").build();

        Result result = controller.approveUser(99L, request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(303);
        assertThat(result.redirectLocation()).contains("/settings");
        verify(repo).approve(99L, "admin");
    }

    @Test
    public void testUnapprovedUserCannotApproveOthers() {
        FormFactory formFactory = mock(FormFactory.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        MessagesApi messagesApi = mock(MessagesApi.class);
        SessionStore sessionStore = mock(SessionStore.class);
        UserAccessRepository repo = mock(UserAccessRepository.class);

        when(repo.isUserApproved("attacker-1")).thenReturn(CompletableFuture.completedFuture(false));

        SettingsController controller = new SettingsController(formFactory, ec, messagesApi, sessionStore, repo);

        CommonProfile profile = new CommonProfile();
        profile.setId("attacker-1");

        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));

        Http.Request request = Helpers.fakeRequest("POST", "/settings/approveUser/99").build();

        Result result = controller.approveUser(99L, request).toCompletableFuture().join();

        assertThat(result.status()).isEqualTo(403);
        verify(repo, never()).approve(any(), any());
    }
}
