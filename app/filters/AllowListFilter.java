package filters;

import akka.stream.Materializer;
import helpers.UserHelpers;
import models.UserAccess;
import models.UserAccessRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.UserProfile;
import play.libs.Json;
import play.mvc.Filter;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.Results;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

@Singleton
public class AllowListFilter extends Filter {

    private final SessionStore playSessionStore;
    private final UserAccessRepository userAccessRepository;

    @Inject
    public AllowListFilter(Materializer mat,
                           SessionStore playSessionStore,
                           UserAccessRepository userAccessRepository) {
        super(mat);
        this.playSessionStore = playSessionStore;
        this.userAccessRepository = userAccessRepository;
    }

    @Override
    public CompletionStage<Result> apply(Function<Http.RequestHeader, CompletionStage<Result>> next, Http.RequestHeader rh) {
        String path = rh.path();

        // 1. Allow publicly accessible & auth infrastructure routes
        if (path.equals("/") ||
            path.equals("/about") ||
            path.equals("/health") ||
            path.startsWith("/assets/") ||
            path.startsWith("/callback") ||
            path.startsWith("/logout") ||
            path.startsWith("/centralLogout") ||
            path.startsWith("/api/monzo/webhook") ||
            path.equals("/waitinglist") ||
            path.equals("/waitlinglist") ||
            path.equals("/api/user/accessStatus")) {
            return next.apply(rh);
        }

        // 2. Check if user is authenticated with Keycloak
        List<UserProfile> profiles;
        try {
            profiles = UserHelpers.getAuthProfiles(playSessionStore, rh);
        } catch (Exception e) {
            profiles = null;
        }

        if (profiles == null || profiles.isEmpty()) {
            // Not authenticated yet - pass through so @Secure can trigger Keycloak login
            return next.apply(rh);
        }

        // 3. User is authenticated - check allow list / approval status
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, rh);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return next.apply(rh);
        }

        return userAccessRepository.getOrCreate(sup.getUserId(), sup.getUsername(), sup.getUserEmail())
                .thenCompose(userAccess -> {
                    if (userAccess != null && userAccess.isApproved()) {
                        return next.apply(rh);
                    }

                    // User is on waiting list (PENDING or REJECTED)
                    // Block ALL API, data mutation, and data retrieval endpoints
                    String method = rh.method().toUpperCase();
                    boolean isMutation = method.equals("POST") || method.equals("PUT") || method.equals("DELETE") || method.equals("PATCH");
                    boolean isApiOrJson = path.startsWith("/api/") ||
                            path.equals("/accounts") ||
                            path.equals("/outgoings") ||
                            path.equals("/incomings") ||
                            path.equals("/balances") ||
                            path.equals("/plans") ||
                            path.startsWith("/bin") ||
                            path.startsWith("/edit") ||
                            rh.header("Accept").map(h -> h.contains("application/json")).orElse(false) ||
                            rh.header("X-Requested-With").map(h -> h.equalsIgnoreCase("XMLHttpRequest")).orElse(false);

                    if (isMutation || isApiOrJson) {
                        return CompletableFuture.completedFuture(
                                Results.forbidden(Json.newObject()
                                        .put("error", "Waiting list: Your account is pending administrator approval.")
                                        .put("status", "PENDING"))
                                        .as("application/json")
                        );
                    }

                    // For UI page GET requests (e.g. /spog), allow page to render so the UI-blocking modal appears
                    return next.apply(rh);
                });
    }
}
