package controllers;

import helpers.UserHelpers;
import models.UserAccess;
import models.UserAccessRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.i18n.MessagesApi;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class WaitingListController extends Controller {

    private final SessionStore sessionStore;
    private final UserAccessRepository userAccessRepository;
    private final MessagesApi messagesApi;

    @Inject
    public WaitingListController(SessionStore sessionStore,
                                 UserAccessRepository userAccessRepository,
                                 MessagesApi messagesApi) {
        this.sessionStore = sessionStore;
        this.userAccessRepository = userAccessRepository;
        this.messagesApi = messagesApi;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(sessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return CompletableFuture.completedFuture(redirect("/"));
        }

        return userAccessRepository.getOrCreate(sup.getUserId(), sup.getUsername(), sup.getUserEmail())
                .thenApply(access -> {
                    if (access != null && access.isApproved()) {
                        // User has been approved! Redirect straight to SPOG
                        return redirect(routes.SpogController.index());
                    }
                    return ok(views.html.waitingList.render(
                            access,
                            request,
                            sessionStore,
                            messagesApi.preferred(request)
                    ));
                });
    }
}
