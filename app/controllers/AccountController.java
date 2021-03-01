package controllers;

import models.Account;
import models.AccountRepository;
import models.Outgoing;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.time.LocalTime;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static helpers.UserHelpers.getSimpleUserProfile;
import static play.libs.Json.toJson;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 */
public class AccountController extends Controller {

    private final FormFactory formFactory;
    private final AccountRepository accountRepository;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public AccountController(FormFactory formFactory, AccountRepository accountRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.accountRepository = accountRepository;
        this.ec = ec;
    }

    // This doesn't work because of something to do with CSRF protection:
    // HM.
    // Turns out that pac4j has its own csrf protection. We can either send the pac4j
    // token along in the request or turn it off on pac4j?
    // This is done by adding a list of "authorizers". By default,
    // pac4j adds the csrfFilter if no "authorizers" are defined...
    // #RTFM
    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addAccount(final Http.Request request) {
        Account account = formFactory.form(Account.class).bindFromRequest(request).get();
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        account.setUserId(sup.getUserId());
        return accountRepository
                .add(account)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getAccountsComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return accountRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(accountStream -> ok(toJson(accountStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveAccount(int id, final Http.Request request) {
        System.out.println("Deleting account with id : " + id);
        // perhaps just update an 'archived' field here
        return accountRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateAccount(int id, final Http.Request request) {
        Account account = formFactory.form(Account.class).bindFromRequest(request).get();
        return accountRepository
                .update(id, account)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

}
