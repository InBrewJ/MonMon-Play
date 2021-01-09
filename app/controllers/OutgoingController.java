package controllers;

import models.*;
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
    private final Form<Outgoing> form;
    private MessagesApi messagesApi;

    @Inject
    public OutgoingController(FormFactory formFactory, MessagesApi messagesApi, OutgoingRepository outgoingRepository, AccountRepository accountRepository, HttpExecutionContext ec) throws ExecutionException, InterruptedException {
        this.formFactory = formFactory;
        this.outgoingRepository = outgoingRepository;
        this.accountRepository = accountRepository;
        this.form = formFactory.form(Outgoing.class);
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        this.accounts = repoListToList(accountRepository.list());
        this.outgoings = repoListToList(outgoingRepository.list());
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
        return ok(views.html.index.render(asScala(accounts), asScala(outgoings), this.outgoingTotal, this.form, request, messagesApi.preferred(request)));
    }

    public CompletionStage<Result> addOutgoing(final Http.Request request) {
        Outgoing outgoing = formFactory.form(Outgoing.class).bindFromRequest(request).get();
        return outgoingRepository
                .add(outgoing)
                .thenApplyAsync(p -> redirect(routes.OutgoingController.index()), ec.current());
    }

    public CompletionStage<Result> getOutgoings() {
        return outgoingRepository
                .list()
                .thenApplyAsync(personStream -> ok(toJson(personStream.collect(Collectors.toList()))), ec.current());
    }

}
