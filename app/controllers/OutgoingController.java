package controllers;

import models.*;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
import static models.Outgoing.getTotalOutgoings;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 */
public class OutgoingController extends Controller {

    private final FormFactory formFactory;
    private final OutgoingRepository outgoingRepository;
    private final AccountRepository accountRepository;
    private final HttpExecutionContext ec;
    private List<Account> accounts;
    private List<Outgoing> outgoings;
    private Float outgoingTotal;
    private final Form<Outgoing> outgoingForm;
    private final Form<Account> accountForm;
    private final MessagesApi messagesApi;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public OutgoingController(FormFactory formFactory, MessagesApi messagesApi, OutgoingRepository outgoingRepository, AccountRepository accountRepository, HttpExecutionContext ec) throws ExecutionException, InterruptedException {
        this.formFactory = formFactory;
        this.outgoingRepository = outgoingRepository;
        this.accountRepository = accountRepository;
        this.outgoingForm = formFactory.form(Outgoing.class);
        this.accountForm = formFactory.form(Account.class);
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient")
    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        this.accounts = repoListToList(accountRepository.list(sup.getUserId()));
        this.outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
        return ok(
                views.html.index.render(
                        asScala(accounts),
                        asScala(outgoings),
                        this.outgoingTotal,
                        this.outgoingForm,
                        false,
                        this.accountForm,
                        false,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addOutgoing(final Http.Request request) throws ExecutionException, InterruptedException {
        Outgoing outgoing = formFactory.form(Outgoing.class).bindFromRequest(request).get();
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        outgoing.setUserId(sup.getUserId());
        outgoing.setAccount(getAccountFromFormRequest(request));
        return outgoingRepository
                .add(outgoing)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateOutgoing(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Updating outgoing with id : " + id);
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Outgoing outgoing = formFactory.form(Outgoing.class).bindFromRequest(request).get();
        System.out.println("Rent -> " + outgoing.isRent());
        System.out.println("Bill -> " + outgoing.isBill());
        System.out.println("Scheduled -> " + outgoing.isHiddenFromTotal());
        outgoing.setAccount(getAccountFromFormRequest(request));
        // Need to somehow protect the POST here, see MWM-37
        return outgoingRepository
                .update(id, outgoing)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveOutgoing(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Archiving outgoing with id : " + id);
        // perhaps just update an 'archived' field here
        return outgoingRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public Result listOutgoingsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        Outgoing found = outgoingRepository.findById(id).toCompletableFuture().get();
        Form<Outgoing> prefilledOutgoingForm = this.outgoingForm.fill(found);
        this.accounts = repoListToList(accountRepository.list(sup.getUserId()));
        this.outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
        if (!found.getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        return ok(
                views.html.index.render(
                        asScala(accounts),
                        asScala(outgoings),
                        this.outgoingTotal,
                        prefilledOutgoingForm,
                        true,
                        this.accountForm,
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
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        Account found = accountRepository.findById(id).toCompletableFuture().get();
        Form<Account> prefilledAccountForm = this.accountForm.fill(found);
        this.accounts = repoListToList(accountRepository.list(sup.getUserId()));
        this.outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
        if (!found.getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        return ok(
                views.html.index.render(
                        asScala(accounts),
                        asScala(outgoings),
                        this.outgoingTotal,
                        this.outgoingForm,
                        false,
                        prefilledAccountForm,
                        true,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getOutgoings(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return outgoingRepository
                .list(sup.getUserId())
                .thenApplyAsync(personStream -> ok(toJson(personStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getOutgoingsComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return outgoingRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(personStream -> ok(toJson(personStream.collect(Collectors.toList()))), ec.current());
    }

    // weird, roundabout stuff for now...
    // because need to get the account_id from the form
    // or do something like:
    // https://stackoverflow.com/questions/26129994/playframework-2-and-manytoone-form-binding
    // This implementation is essentially the same thing as the SO answer above, it's
    // just defined here rather than in the global scope
    // It could be improved with an accountRepository.findById() method, though
    private Account getAccountFromFormRequest(Http.Request request) throws ExecutionException, InterruptedException {
        int accountIdFromForm = parseInt(request.body().asFormUrlEncoded().get("account_id")[0]);
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        List<Account> desiredAccount = accounts.stream().filter(account -> account.getId() == accountIdFromForm  ).collect(Collectors.toList());
        return desiredAccount.get(0);
    }

}
