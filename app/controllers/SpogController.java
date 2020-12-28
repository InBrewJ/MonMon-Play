package controllers;

import models.*;
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

public class SpogController extends Controller {
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;
    private final AccountRepository accountRepository;
    private final HttpExecutionContext ec;

    @Inject
    public SpogController(OutgoingRepository outgoingRepository, AccountRepository accountRepository, IncomingRepository incomingRepository, HttpExecutionContext ec) {
        this.incomingRepository = incomingRepository;
        this.accountRepository = accountRepository;
        this.outgoingRepository = outgoingRepository;
        this.ec = ec;
    }

    private Float getTotalOutgoings(List<Outgoing> outgoings) {
        return outgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
    }

    private Float round2(Float val) {
        return new BigDecimal(val).setScale(2, RoundingMode.HALF_UP).floatValue();
    }

    private Float getTotalIncomings(List<Incoming> incomings) {
        return incomings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.netValue, Float::sum);
    }

    private <T> List<T> repoListToList(CompletionStage<Stream<T>> in) throws ExecutionException, InterruptedException {
        return in.toCompletableFuture().get().collect(Collectors.toList());
    }

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        // This should show things like:
        // - amount left per day
        // - savings plans and targets
        // - balance entry (which will eventually be automated)
        // - current status compared to the breadline
        // But, for now, show income - outcome
        Float outgoingTotal = getTotalOutgoings(repoListToList(outgoingRepository.list()));
        Float incomingTotal = getTotalIncomings(repoListToList(incomingRepository.list()));
        Float surplus = round2(incomingTotal - outgoingTotal);
        return ok(views.html.spog.render(surplus, request));
    }
}
