package controllers;

import models.Account;
import models.AccountRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.List;
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
 */
public class AccountController extends Controller {

    private final FormFactory formFactory;
    private final AccountRepository accountRepository;
    private final MessagesApi messagesApi;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public AccountController(FormFactory formFactory, MessagesApi messagesApi, AccountRepository accountRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.accountRepository = accountRepository;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient")
    public Result listAccounts(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        return ok(
                views.html.accounts.render(
                        asScala(accounts),
                        formFactory.form(Account.class),
                        false,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient")
    public Result listAccountsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Account found = accountRepository.findById(id).toCompletableFuture().get();
        if (found == null || !found.getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        Form<Account> prefilledAccountForm = formFactory.form(Account.class).fill(found);
        return ok(
                views.html.accounts.render(
                        asScala(accounts),
                        prefilledAccountForm,
                        true,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addAccount(final Http.Request request) {
        Account account = formFactory.form(Account.class).bindFromRequest(request).get();
        System.out.println("this limit :: " + account.getAvailableLimit());
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        account.setUserId(sup.getUserId());
        return accountRepository
                .add(account)
                .thenApplyAsync(p -> redirect(routes.AccountController.listAccounts()), ec.current());
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
        return accountRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.AccountController.listAccounts()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateAccount(int id, final Http.Request request) {
        Account account = formFactory.form(Account.class).bindFromRequest(request).get();
        System.out.println("this limit :: " + account.getAvailableLimit());
        return accountRepository
                .update(id, account)
                .thenApplyAsync(p -> redirect(routes.AccountController.listAccounts()), ec.current());
    }

}
