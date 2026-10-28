package controllers;

import helpers.BankHolidayHelper;
import models.UserAccess;
import models.UserAccessRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static helpers.UserHelpers.getSimpleUserProfile;

public class SettingsController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final MessagesApi messagesApi;
    private final SessionStore sessionStore;
    private final UserAccessRepository userAccessRepository;

    @Inject
    public SettingsController(FormFactory formFactory,
                              HttpExecutionContext ec,
                              MessagesApi messagesApi,
                              SessionStore sessionStore,
                              UserAccessRepository userAccessRepository) {
        this.formFactory = formFactory;
        this.ec = ec;
        this.messagesApi = messagesApi;
        this.sessionStore = sessionStore;
        this.userAccessRepository = userAccessRepository;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(Http.Request request) {
        String region = request.session().get("region").orElse("UK");
        SimpleUserProfile sup = getSimpleUserProfile(sessionStore, request);

        // Ensure current user is registered in user_access (auto-approves if initial admin or has accounts)
        return userAccessRepository.getOrCreate(sup.getUserId(), sup.getUsername(), sup.getUserEmail())
                .thenCompose(currentAccess ->
                        userAccessRepository.listPending().thenCombine(
                                userAccessRepository.listApproved(),
                                (pending, approved) -> ok(views.html.settings.render(
                                        region,
                                        helpers.BuildInfo.getBuildNumber(),
                                        helpers.BuildInfo.getBuildTimestamp(),
                                        helpers.BuildInfo.getBuildCommit(),
                                        pending,
                                        approved,
                                        request,
                                        this.sessionStore,
                                        messagesApi.preferred(request)
                                ))
                        )
                );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result updateSettings(Http.Request request) {
        DynamicForm requestData = formFactory.form().bindFromRequest(request);
        String region = Optional.ofNullable(requestData.get("region")).orElse("UK");
        return redirect(routes.SettingsController.index())
                .addingToSession(request, "region", region)
                .flashing("success", "Settings updated successfully! Region set to: " + region);
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> approveUser(Long id, Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(sessionStore, request);
        return userAccessRepository.isUserApproved(sup.getUserId())
                .thenCompose(approved -> {
                    if (!approved) {
                        return CompletableFuture.completedFuture(forbidden("You are not authorized to approve users."));
                    }
                    return userAccessRepository.approve(id, sup.getUsername())
                            .thenApply(user -> {
                                String name = user != null ? user.getUsername() : "User #" + id;
                                return redirect(routes.SettingsController.index())
                                        .flashing("success", "Approved user " + name + "! They now have full access to MonMon and NomMon.");
                            });
                });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> rejectUser(Long id, Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(sessionStore, request);
        return userAccessRepository.isUserApproved(sup.getUserId())
                .thenCompose(approved -> {
                    if (!approved) {
                        return CompletableFuture.completedFuture(forbidden("You are not authorized to reject users."));
                    }
                    return userAccessRepository.reject(id, sup.getUsername())
                            .thenApply(user -> {
                                String name = user != null ? user.getUsername() : "User #" + id;
                                return redirect(routes.SettingsController.index())
                                        .flashing("success", "Rejected access for " + name + ".");
                            });
                });
    }
}
