package controllers;

import models.*;
import services.GoogleCalendarService;
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
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 */
public class BalanceController extends Controller {

    private final FormFactory formFactory;
    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final HttpExecutionContext ec;
    private final Form<Balance> form;
    private final GoogleCalendarService gcalService;
    private MessagesApi messagesApi;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public BalanceController(FormFactory formFactory,
                             MessagesApi messagesApi,
                             BalanceRepository balanceRepository,
                             AccountRepository accountRepository,
                             HttpExecutionContext ec,
                             GoogleCalendarService gcalService) {
        this.formFactory = formFactory;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.messagesApi = messagesApi;
        this.form = formFactory.form(Balance.class);
        this.ec = ec;
        this.gcalService = gcalService;
    }

    // weird, roundabout stuff for now...
    // because need to get the account_id from the form
    // or do something like:
    // https://stackoverflow.com/questions/26129994/playframework-2-and-manytoone-form-binding
    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addBalance(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Balance balance = formFactory.form(Balance.class).bindFromRequest(request).get();
        balance.setTimestamp(generateUnixTimestamp());
        int accountIdFromForm = parseInt(request.body().asFormUrlEncoded().get("account_id")[0]);
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        Account desiredAccount = accounts
                .stream()
                .filter(account -> account.getId() == accountIdFromForm  )
                .collect(Collectors.toList()).get(0);
        if (!desiredAccount.getUserId().equals(sup.getUserId())) {
            CompletableFuture.runAsync(() -> {
                forbidden(views.html.error403.render());
            });
        }
        balance.setAccount(desiredAccount);
        balance.setUserId(sup.getUserId());
        return balanceRepository
                .add(balance)
                .thenApplyAsync(p -> redirect(routes.BalanceController.listBalances()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getBalances(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return balanceRepository
                .list(sup.getUserId())
                .thenApplyAsync(balanceStream -> ok(toJson(balanceStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> listBalances(Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Balance> balances = repoListToList(balanceRepository.list(sup.getUserId()));
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate oneYearAgo = now.minusYears(1);

        return gcalService.getEventsForYear(oneYearAgo, now)
                .thenApplyAsync(events -> ok(
                        views.html.balances.render(
                                asScala(balances),
                                this.form,
                                asScala(accounts),
                                asScala(events),
                                request,
                                playSessionStore,
                                messagesApi.preferred(request)
                        )
                ), ec.current());
    }

}
