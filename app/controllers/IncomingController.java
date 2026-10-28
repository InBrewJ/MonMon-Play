package controllers;

import models.Incoming;
import models.IncomingRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.FormFactory;
import play.data.Form;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 *
 * */

public class IncomingController extends Controller {
    private final FormFactory formFactory;
    private final IncomingRepository incomingRepository;
    private final models.AccountRepository accountRepository;
    private final HttpExecutionContext ec;
    private final Form<Incoming> form;
    private MessagesApi messagesApi;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public IncomingController(FormFactory formFactory, MessagesApi messagesApi, IncomingRepository incomingRepository, models.AccountRepository accountRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.form = formFactory.form(Incoming.class);
        this.incomingRepository = incomingRepository;
        this.accountRepository = accountRepository;
        this.ec = ec;
    }

    public IncomingController(FormFactory formFactory, MessagesApi messagesApi, IncomingRepository incomingRepository, HttpExecutionContext ec) {
        this(formFactory, messagesApi, incomingRepository, null, ec);
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> getIncomings(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return CompletableFuture.completedFuture(unauthorized(play.libs.Json.newObject().put("error", "Unauthorized")));
        }
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        return incomingRepository
                .list(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> getIncomingsComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return CompletableFuture.completedFuture(unauthorized(play.libs.Json.newObject().put("error", "Unauthorized")));
        }
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        return incomingRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result listIncomings(Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        List<models.Account> accounts = accountRepository != null ? repoListToList(accountRepository.list(sup.getUserId())) : Collections.emptyList();
        return ok(views.html.incomings.render(
                asScala(incomings),
                this.form,
                asScala(accounts),
                false,
                request,
                playSessionStore,
                messagesApi.preferred(request))
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result listIncomingsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        List<models.Account> accounts = accountRepository != null ? repoListToList(accountRepository.list(sup.getUserId())) : Collections.emptyList();
        Incoming found = incomingRepository.findById(id).toCompletableFuture().get();
        if (!found.getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        Form<Incoming> prefilledForm = this.form.fill(found);
        System.out.println("prefilled name: " + prefilledForm.field("name").value());
        //
        return ok(views.html.incomings.render(
                asScala(incomings),
                prefilledForm,
                asScala(accounts),
                true,
                request,
                playSessionStore,
                messagesApi.preferred(request))
        );
    }

    private models.Account getAccountFromFormRequest(Http.Request request, String userId) throws ExecutionException, InterruptedException {
        if (accountRepository == null) {
            return null;
        }
        Map<String, String[]> form = request.body().asFormUrlEncoded();
        if (form == null) return null;
        String[] accIdArr = form.get("account_id");
        if (accIdArr == null || accIdArr.length == 0 || accIdArr[0].isEmpty()) {
            accIdArr = form.get("account.id");
        }
        if (accIdArr == null || accIdArr.length == 0 || accIdArr[0].isEmpty()) {
            return null;
        }
        try {
            long accountIdFromForm = Long.parseLong(accIdArr[0]);
            List<models.Account> accounts = repoListToList(accountRepository.list(userId));
            return accounts.stream().filter(a -> a.getId() != null && a.getId() == accountIdFromForm).findFirst().orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        System.out.println("Updating Incoming with id : " + id);
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        incoming.setAccount(getAccountFromFormRequest(request, sup.getUserId()));
        // Need to somehow check the userId vs the incoming to be archived
        // Ideally there would be a way to keep from passing userId into
        // incomingRepository?
        return incomingRepository
                .update(id, incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addIncoming(final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        incoming.setUserId(sup.getUserId());
        incoming.setAccount(getAccountFromFormRequest(request, sup.getUserId()));
        return incomingRepository
                .add(incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        System.out.println("Deleting Incoming with id : " + id);
        // perhaps just update an 'archived' field here
        return incomingRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }
}
