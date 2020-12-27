package controllers;

import models.Account;
import models.AccountRepository;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.time.LocalTime;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

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
    public AccountController(FormFactory formFactory, AccountRepository accountRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.accountRepository = accountRepository;
        this.ec = ec;
    }

    public CompletionStage<Result> addAccount(final Http.Request request) {
        Account account = formFactory.form(Account.class).bindFromRequest(request).get();
        return accountRepository
                .add(account)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    public CompletionStage<Result> getAccounts() {
        return accountRepository
                .list()
                .thenApplyAsync(accountStream -> ok(toJson(accountStream.collect(Collectors.toList()))), ec.current());
    }

    public Result numTypes() {
//        returns the number of each type of account
        return ok("40 of each! And the time is: " + LocalTime.now().toString());
    }

}
