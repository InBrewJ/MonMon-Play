package controllers;

import models.*;
import org.pac4j.play.java.Secure;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
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
        this.accounts = repoListToList(accountRepository.list());
        this.outgoings = repoListToList(outgoingRepository.list());
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
                        messagesApi.preferred(request)
                )
        );
    }

    private Account getAccountFromFormRequest(Http.Request request) throws ExecutionException, InterruptedException {
        // weird, roundabout stuff for now...
        // because need to get the account_id from the form
        // or do something like:
        // https://stackoverflow.com/questions/26129994/playframework-2-and-manytoone-form-binding
        // This implementation is essentially the same thing as the SO answer above, it's
        // just defined here rather than in the global scope
        // It could be improved with an accountRepository.findById() method, though
        int accountIdFromForm = parseInt(request.body().asFormUrlEncoded().get("account_id")[0]);
        List<Account> accounts = repoListToList(accountRepository.list());
        List<Account> desiredAccount = accounts.stream().filter(account -> account.getId() == accountIdFromForm  ).collect(Collectors.toList());
        return desiredAccount.get(0);
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> addOutgoing(final Http.Request request) throws ExecutionException, InterruptedException {
        Outgoing outgoing = formFactory.form(Outgoing.class).bindFromRequest(request).get();
        outgoing.setAccount(getAccountFromFormRequest(request));
        return outgoingRepository
                .add(outgoing)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> updateOutgoing(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Updating outgoing with id : " + id);
        Outgoing outgoing = formFactory.form(Outgoing.class).bindFromRequest(request).get();
        outgoing.setAccount(getAccountFromFormRequest(request));
        // Update all fields here
        return outgoingRepository
                .update(id, outgoing)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> archiveOutgoing(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Archiving outgoing with id : " + id);
        // perhaps just update an 'archived' field here
        return outgoingRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public Result listOutgoingsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list());
        Outgoing found = outgoingRepository.findById(id).toCompletableFuture().get();
        Form<Outgoing> prefilledOutgoingForm = this.outgoingForm.fill(found);
        this.accounts = repoListToList(accountRepository.list());
        this.outgoings = repoListToList(outgoingRepository.list());
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
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
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient")
    public Result listAccountsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list());
        Account found = accountRepository.findById(id).toCompletableFuture().get();
        Form<Account> prefilledAccountForm = this.accountForm.fill(found);
        this.accounts = repoListToList(accountRepository.list());
        this.outgoings = repoListToList(outgoingRepository.list());
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
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
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getOutgoings() {
        return outgoingRepository
                .list()
                .thenApplyAsync(personStream -> ok(toJson(personStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getOutgoingsComplete() {
        return outgoingRepository
                .listComplete()
                .thenApplyAsync(personStream -> ok(toJson(personStream.collect(Collectors.toList()))), ec.current());
    }

}
