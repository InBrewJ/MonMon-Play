package controllers;

import models.Account;
import models.AccountRepository;
import models.Outgoing;
import models.OutgoingRepository;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    @Inject
    public OutgoingController(FormFactory formFactory, OutgoingRepository outgoingRepository, AccountRepository accountRepository, HttpExecutionContext ec) throws ExecutionException, InterruptedException {
        this.formFactory = formFactory;
        this.outgoingRepository = outgoingRepository;
        this.accountRepository = accountRepository;
        this.ec = ec;
    }

    private Float round2(Float val) {
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    private Float getTotalOutgoings(List<Outgoing> outgoings) {
        return outgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    private <T> List<T> repoListToList(CompletionStage<Stream<T>> in) throws ExecutionException, InterruptedException {
        return in.toCompletableFuture().get().collect(Collectors.toList());
    }

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        this.accounts = repoListToList(accountRepository.list());
        this.outgoings = repoListToList(outgoingRepository.list());
        this.outgoingTotal = round2(getTotalOutgoings(this.outgoings));
        return ok(views.html.index.render(asScala(accounts), asScala(outgoings), this.outgoingTotal, request));
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
