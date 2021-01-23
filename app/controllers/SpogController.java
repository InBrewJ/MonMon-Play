package controllers;

import models.*;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.Spog;

import javax.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static models.Incoming.getTotalIncomings;
import static models.Outgoing.getTotalOutgoings;

public class SpogController extends Controller {
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;
    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final HttpExecutionContext ec;

    @Inject
    public SpogController(OutgoingRepository outgoingRepository, AccountRepository accountRepository, IncomingRepository incomingRepository, BalanceRepository balanceRepository, HttpExecutionContext ec) {
        this.incomingRepository = incomingRepository;
        this.accountRepository = accountRepository;
        this.outgoingRepository = outgoingRepository;
        this.balanceRepository = balanceRepository;
        this.ec = ec;
    }

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        Float outgoingTotal = getTotalOutgoings(repoListToList(outgoingRepository.list()));
        Float incomingTotal = getTotalIncomings(repoListToList(incomingRepository.list()));
        List<Outgoing> rents = repoListToList(outgoingRepository.rents());
        Float rentCost = !rents.isEmpty() ? rents.get(0).cost : 0;
        Float surplus = round2(incomingTotal - outgoingTotal);
        int suggestedIncomeAsSavings = 20;
        int nextPayDay = incomingRepository.getNextPayDay();
        // Scratch
        List<Outgoing> completedOutgoings = repoListToList(outgoingRepository.alreadyPaid(LocalDate.now(), nextPayDay));
        for (Outgoing o: completedOutgoings) {
            System.out.println("Already paid :: " + o.getName() + " on " + o.getOutgoingDay());
        }
        List<Outgoing> pendingOutgoings = repoListToList(outgoingRepository.yetToPay(LocalDate.now(), nextPayDay));
        for (Outgoing o: pendingOutgoings) {
            System.out.println("Pending :: " + o.getName() + " on " + o.getOutgoingDay());
        }
        List<Outgoing> bills = repoListToList(outgoingRepository.bills());
        for (Outgoing o: bills) {
            System.out.println("Bill :: " + o.getName() + " on " + o.getOutgoingDay());
        }
        // Scratch end
        Spog spogVm = new Spog(surplus, nextPayDay, suggestedIncomeAsSavings, incomingTotal, outgoingTotal, rentCost);
        return ok(views.html.spog.render(spogVm, request));
    }

    public Result seed(final Http.Request request) throws ExecutionException, InterruptedException {
        // wtf is this
        // should probably be able to
        // do this in a separate class
        // with hibernate native things
        //
        //
        CompletionStage<Account> back;
        Account natwestC = new Account();
        natwestC.setName("Natwest Credit");
        natwestC.setType("Credit");
        natwestC.setNickname("The grad one");
        back = this.accountRepository.add(natwestC);
        long natwestCreditId = back.toCompletableFuture().get().getId();
        System.out.println("natwest credit id : " + natwestCreditId);
        //
        Account natwestD = new Account();
        natwestD.setName("Natwest Debit");
        natwestD.setNickname("main overflow");
        back = this.accountRepository.add(natwestD);
        long natwestDebitId = back.toCompletableFuture().get().getId();
        //
        Account lloyds = new Account();
        lloyds.setName("Lloyds");
        natwestD.setNickname("salary in / bill account");
        back = this.accountRepository.add(lloyds);
        long lloydsDebitId = back.toCompletableFuture().get().getId();
        //
        Account halifax = new Account();
        halifax.setName("Halifax");
        natwestD.setNickname("daily driver");
        back = this.accountRepository.add(halifax);
        long halifaxDebitId = back.toCompletableFuture().get().getId();
        //
        Incoming salary = new Incoming();
        salary.setName("ovo");
        salary.setNetValue(2800f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(29);
        salary.setType("salary");
        this.incomingRepository.add(salary);
        //
        Outgoing rent = new Outgoing();
        rent.setRent(true);
        rent.setCost(1700f);
        rent.setOutgoingDay(1);
        rent.setName("Rent");
        rent.setFromAccount((int) lloydsDebitId);
        this.outgoingRepository.add(rent);
        //
        Outgoing water = new Outgoing();
        water.setRent(true);
        water.setCost(28.81f);
        water.setOutgoingDay(1);
        water.setName("Water bill");
        water.setFromAccount((int) lloydsDebitId);
        this.outgoingRepository.add(water);
        //
        Outgoing spotify = new Outgoing();
        spotify.setCost(9.99f);
        spotify.setOutgoingDay(18);
        spotify.setName("Spotify");
        spotify.setFromAccount((int) natwestCreditId);
        this.outgoingRepository.add(spotify);
        //
        Outgoing fitbit = new Outgoing();
        fitbit.setCost(7.99f);
        fitbit.setOutgoingDay(18);
        fitbit.setName("Fitbit premium");
        fitbit.setFromAccount((int) halifaxDebitId);
        this.outgoingRepository.add(fitbit);
        //
        Outgoing nuranow = new Outgoing();
        nuranow.setCost(9.99f);
        nuranow.setOutgoingDay(28);
        nuranow.setName("Nuraphones");
        nuranow.setFromAccount((int) natwestCreditId);
        this.outgoingRepository.add(nuranow);
        return ok("Seeded");
    }
}
