package controllers;

import models.*;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.Spog;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.ExecutionException;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static models.Incoming.getTotalIncomings;
import static models.Outgoing.getTotalOutgoings;

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

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        Float outgoingTotal = getTotalOutgoings(repoListToList(outgoingRepository.list()));
        Float incomingTotal = getTotalIncomings(repoListToList(incomingRepository.list()));
        List<Outgoing> rents = repoListToList(outgoingRepository.rents());
        Float rentCost = rents.get(0).cost;
        Float surplus = round2(incomingTotal - outgoingTotal);
        int suggestedIncomeAsSavings = 20;
        int nextPayDay = incomingRepository.getNextPayDay();
        Spog spogVm = new Spog(surplus, nextPayDay, suggestedIncomeAsSavings, incomingTotal, outgoingTotal, rentCost);
        return ok(views.html.spog.render(spogVm, request));
    }
}
