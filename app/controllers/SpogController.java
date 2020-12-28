package controllers;

import models.*;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;

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

    private Float getTotalIncomings(List<Incoming> incomings) {
        return incomings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.netValue, Float::sum);
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
