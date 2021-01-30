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
import static helpers.TimeHelpers.generateUnixTimestamp;
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
        System.out.println("C_Outgoings sum: " + completedOutgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum));
        List<Outgoing> pendingOutgoings = repoListToList(outgoingRepository.yetToPay(LocalDate.now(), nextPayDay));
        for (Outgoing o: pendingOutgoings) {
            System.out.println("Pending :: " + o.getName() + " on " + o.getOutgoingDay());
        }
        System.out.println("P_Outgoings sum: " + pendingOutgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum));
        List<Outgoing> bills = repoListToList(outgoingRepository.bills());
        for (Outgoing o: bills) {
            System.out.println("Bill :: " + o.getName() + " on " + o.getOutgoingDay());
        }
        Float completedOutgoingsSum = completedOutgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
        Float pendingOutgoingsSum = pendingOutgoings.stream().reduce(0.0f, (partialResult, o) -> partialResult + o.cost, Float::sum);
        // Scratch end
        List<Account> allAccounts = repoListToList(this.accountRepository.list());
        Spog spogVm = new Spog(
                surplus,
                nextPayDay,
                suggestedIncomeAsSavings,
                incomingTotal,
                outgoingTotal,
                rentCost,
                completedOutgoingsSum,
                pendingOutgoingsSum,
                allAccounts);
        return ok(views.html.spog.render(spogVm, request));
    }

    public Result seed(final Http.Request request) throws ExecutionException, InterruptedException {
        // wtf is this
        // should probably be able to
        // do this in a separate class
        // with hibernate native things...
        // at least this way it's decoupled
        // from anything the database side of ORM
        // and even, to some extent, even hibernate
        // Accounts
        CompletionStage<Account> back;
        Account natwestCFullAccount;
        Account natwestC = new Account();
        natwestC.setName("Natwest Credit");
        natwestC.setType("Credit");
        natwestC.setNickname("The grad one");
        natwestCFullAccount = this.accountRepository.add(natwestC).toCompletableFuture().get();
        long natwestCreditId = natwestCFullAccount.getId();
        //
        Account natwestD = new Account();
        natwestD.setName("Natwest Debit");
        natwestD.setType("Debit");
        natwestD.setNickname("main overflow");
        back = this.accountRepository.add(natwestD);
        //
        Account vanquis = new Account();
        Account vanquisFullAccount;
        vanquis.setName("Vanquis");
        vanquis.setType("Credit");
        vanquis.setNickname("The builder");
        vanquisFullAccount = this.accountRepository.add(vanquis).toCompletableFuture().get();
        //
        Account lloyds = new Account();
        Account lloydsFullAccount;
        lloyds.setName("Lloyds");
        lloyds.setType("Debit");
        natwestD.setNickname("salary in / bill account");
        lloydsFullAccount = this.accountRepository.add(lloyds).toCompletableFuture().get();
        //
        Account halifax = new Account();
        Account halifaxFullAccount;
        halifax.setName("Halifax");
        halifax.setType("Debit");
        natwestD.setNickname("daily driver");
        halifaxFullAccount = this.accountRepository.add(halifax).toCompletableFuture().get();
        // Incomings
        Incoming salary = new Incoming();
        salary.setName("ovo");
        salary.setNetValue(2859.79f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(26);
        salary.setType("salary");
        this.incomingRepository.add(salary);
        // Outgoings
        Outgoing rent = new Outgoing();
        rent.setRent(true);
        rent.setCost(1700f);
        rent.setOutgoingDay(1);
        rent.setName("Rent");
        rent.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(rent);
        //
        Outgoing water = new Outgoing();
        water.setBill(true);
        water.setCost(28.81f);
        water.setOutgoingDay(1);
        water.setName("Water bill");
        water.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(water);
        //
        Outgoing councilTax = new Outgoing();
        councilTax.setBill(true);
        councilTax.setCost(149f);
        councilTax.setOutgoingDay(1);
        councilTax.setName("Council tax");
        councilTax.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(councilTax);
        //
        Outgoing spotify = new Outgoing();
        spotify.setCost(9.99f);
        spotify.setOutgoingDay(18);
        spotify.setName("Spotify");
        spotify.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(spotify);
        //
        Outgoing fitbit = new Outgoing();
        fitbit.setCost(7.99f);
        fitbit.setOutgoingDay(18);
        fitbit.setName("Fitbit premium");
        fitbit.setAccount(halifaxFullAccount);
        this.outgoingRepository.add(fitbit);
        //
        Outgoing nuranow = new Outgoing();
        nuranow.setCost(9.99f);
        nuranow.setOutgoingDay(28);
        nuranow.setName("Nuraphones");
        nuranow.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(nuranow);
        // Balances
        Balance natwestCreditBalance0 = new Balance();
        natwestCreditBalance0.setAccount(natwestCFullAccount);
        natwestCreditBalance0.setValue(-500d);
        natwestCreditBalance0.setTimestamp(generateUnixTimestamp()-86400);
        this.balanceRepository.add(natwestCreditBalance0);
        Balance natwestCreditBalance1 = new Balance();
        natwestCreditBalance1.setAccount(natwestCFullAccount);
        natwestCreditBalance1.setValue(-123d);
        natwestCreditBalance1.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(natwestCreditBalance1);
        //
        Balance lloydsBalance = new Balance();
        lloydsBalance.setAccount(lloydsFullAccount);
        lloydsBalance.setValue(47.49d);
        lloydsBalance.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(lloydsBalance);
        //
        Balance vanquisBalance = new Balance();
        vanquisBalance.setAccount(vanquisFullAccount);
        vanquisBalance.setValue(-200d);
        vanquisBalance.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(vanquisBalance);
        Balance vanquisBalance1 = new Balance();
        vanquisBalance1.setAccount(vanquisFullAccount);
        vanquisBalance1.setValue(-800d);
        vanquisBalance1.setTimestamp(generateUnixTimestamp()-(2*86400));
        this.balanceRepository.add(vanquisBalance1);

        return ok("Seeded");
    }
}
