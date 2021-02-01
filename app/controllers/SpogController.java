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
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static models.Incoming.getTotalIncomings;
import static models.Outgoing.getTotalOutgoings;
import static models.Outgoing.getTotalOutgoingsWithoutHidden;

public class SpogController extends Controller {
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;
    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final PlanRepository planRepository;
    private final HttpExecutionContext ec;

    @Inject
    public SpogController(PlanRepository planRepository, OutgoingRepository outgoingRepository, AccountRepository accountRepository, IncomingRepository incomingRepository, BalanceRepository balanceRepository, HttpExecutionContext ec) {
        this.incomingRepository = incomingRepository;
        this.accountRepository = accountRepository;
        this.outgoingRepository = outgoingRepository;
        this.balanceRepository = balanceRepository;
        this.planRepository = planRepository;
        this.ec = ec;
    }

    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        // Plans affect how total outgoings and rent values appear
        List<Plan> allPlans = repoListToList(planRepository.list());
        Plan firstRentShare = !allPlans.isEmpty() ?
                allPlans
                        .stream()
                        .filter(p -> p.getType() == Plan.PlanType.RENT_SHARE)
                        .collect(Collectors.toList())
                        .get(0) : null;
        Plan firstBillShare = !allPlans.isEmpty() ?
                allPlans
                        .stream()
                        .filter(p -> p.getType() == Plan.PlanType.BILL_SHARE)
                        .collect(Collectors.toList())
                        .get(0) : null;
        //
        List<Outgoing> allOutgoings = repoListToList(outgoingRepository.list());
        // MWM-28 if a bill share exists, find the bills in all outgoings and divide here
        for (Outgoing o : allOutgoings) {
            if (firstBillShare != null && o.isBill() ) {
                o.setCost(o.getCost() * firstBillShare.getSplit());
            }
            if (firstRentShare != null && o.isRent() ) {
                o.setCost(o.getCost() * firstRentShare.getSplit());
            }
        }

        Float outgoingTotal = round2(getTotalOutgoingsWithoutHidden(allOutgoings));
        Float incomingTotal = getTotalIncomings(repoListToList(incomingRepository.list()));
        List<Outgoing> rents = repoListToList(outgoingRepository.rents());
        Float rentCost = !rents.isEmpty() ? rents.get(0).cost : 0;
        if (firstRentShare != null) {
            System.out.println("Rent share : " + firstRentShare.getSplit());
            rentCost = rentCost * firstRentShare.getSplit();
        }

        Float surplus = round2(incomingTotal - outgoingTotal);
        int suggestedIncomeAsSavings = 46;
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
        Account natwestCFullAccount;
        Account natwestC = new Account();
        natwestC.setName("Natwest Credit");
        natwestC.setType(Account.AccountType.CREDIT);
        natwestC.setNickname("The grad one");
        natwestCFullAccount = this.accountRepository.add(natwestC).toCompletableFuture().get();
        long natwestCreditId = natwestCFullAccount.getId();
        //
        Account natwestD = new Account();
        Account natwestDFullAccount;
        natwestD.setName("Natwest Debit");
        natwestD.setType(Account.AccountType.DEBIT);
        natwestD.setNickname("main overflow");
        natwestDFullAccount = this.accountRepository.add(natwestD).toCompletableFuture().get();
        long natwestDebitId = natwestDFullAccount.getId();
        //
        Account vanquis = new Account();
        Account vanquisFullAccount;
        vanquis.setName("Vanquis");
        vanquis.setType(Account.AccountType.CREDIT);
        vanquis.setNickname("The builder");
        vanquisFullAccount = this.accountRepository.add(vanquis).toCompletableFuture().get();
        //
        Account lloyds = new Account();
        Account lloydsFullAccount;
        lloyds.setName("Lloyds");
        lloyds.setType(Account.AccountType.DEBIT_SHARED_BILLS);
        natwestD.setNickname("salary in / bill account");
        lloydsFullAccount = this.accountRepository.add(lloyds).toCompletableFuture().get();
        //
        Account halifax = new Account();
        Account halifaxFullAccount;
        halifax.setName("Halifax");
        halifax.setType(Account.AccountType.DEBIT);
        natwestD.setNickname("daily driver");
        halifaxFullAccount = this.accountRepository.add(halifax).toCompletableFuture().get();
        // Incomings
        Incoming salary = new Incoming();
        salary.setName("ovo");
        salary.setNetValue(2839.04f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(26);
        salary.setType("salary");
        this.incomingRepository.add(salary);
        // Incomings end
        // Outgoings
        // Rent and bills
        Outgoing rent = new Outgoing();
        rent.setRent(true);
        rent.setCost(1700f);
        rent.setOutgoingDay(1);
        rent.setName("Rent");
        rent.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(rent);
        //
        Outgoing virgin = new Outgoing();
        virgin.setBill(true);
        virgin.setCost(27f);
        virgin.setOutgoingDay(3);
        virgin.setName("Virgin media");
        virgin.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(virgin);
        //
        Outgoing water = new Outgoing();
        water.setBill(true);
        water.setCost(28.62f);
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
        Outgoing ovoEnergy = new Outgoing();
        ovoEnergy.setBill(true);
        ovoEnergy.setCost(75f);
        ovoEnergy.setOutgoingDay(1);
        ovoEnergy.setName("Ovo Gaz e Luce");
        ovoEnergy.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(ovoEnergy);
        // Rest of outgoings
        Outgoing audible = new Outgoing();
        audible.setCost(7.99f);
        audible.setOutgoingDay(10);
        audible.setName("Audible");
        audible.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(audible);
        //
        Outgoing yousician = new Outgoing();
        yousician.setCost(12.99f);
        yousician.setOutgoingDay(13);
        yousician.setName("Yousician");
        yousician.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(yousician);
        //
        Outgoing savingsPayoff = new Outgoing();
        savingsPayoff.setCost(1305.96f);
        savingsPayoff.setOutgoingDay(26);
        savingsPayoff.setName("Savings/Payoff");
        savingsPayoff.setAccount(lloydsFullAccount);
        this.outgoingRepository.add(savingsPayoff);
        //
        Outgoing natwestCreditPayoff = new Outgoing();
        natwestCreditPayoff.setCost(13.92f);
        natwestCreditPayoff.setOutgoingDay(15);
        natwestCreditPayoff.setName("Natwest Credit Card");
        natwestCreditPayoff.setAccount(natwestDFullAccount);
        this.outgoingRepository.add(natwestCreditPayoff);
        //
        Outgoing MSOffice = new Outgoing();
        MSOffice.setCost(9.48f);
        MSOffice.setOutgoingDay(10);
        MSOffice.setName("MS Office (gps)");
        MSOffice.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(MSOffice);
        //
        Outgoing o2bill = new Outgoing();
        o2bill.setCost(57.46f);
        o2bill.setOutgoingDay(18);
        o2bill.setName("O2 bill");
        o2bill.setAccount(natwestDFullAccount);
        this.outgoingRepository.add(o2bill);
        //
        Outgoing spotify = new Outgoing();
        spotify.setCost(9.99f);
        spotify.setOutgoingDay(18);
        spotify.setName("Spotify");
        spotify.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(spotify);
        //
        Outgoing netflix = new Outgoing();
        netflix.setCost(9.99f);
        netflix.setOutgoingDay(21);
        netflix.setName("Netflix");
        netflix.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(netflix);
        //
        Outgoing medium = new Outgoing();
        medium.setCost(3.87f);
        medium.setOutgoingDay(21);
        medium.setName("Medium");
        medium.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(medium);
        //
        Outgoing nuranow = new Outgoing();
        nuranow.setCost(9.99f);
        nuranow.setOutgoingDay(28);
        nuranow.setName("Nuraphones");
        nuranow.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(nuranow);
        //
        Outgoing urawizardSite = new Outgoing();
        urawizardSite.setCost(6f);
        urawizardSite.setOutgoingDay(6);
        urawizardSite.setName("urawizard.com");
        urawizardSite.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(urawizardSite);
        //
        Outgoing guardian = new Outgoing();
        guardian.setCost(11.99f);
        guardian.setOutgoingDay(7);
        guardian.setName("Guardian w/Crosswords");
        guardian.setAccount(natwestCFullAccount);
        this.outgoingRepository.add(guardian);
        //
        Outgoing fitbit = new Outgoing();
        fitbit.setCost(7.99f);
        fitbit.setOutgoingDay(18);
        fitbit.setName("Fitbit premium");
        fitbit.setAccount(halifaxFullAccount);
        this.outgoingRepository.add(fitbit);
        //
        Outgoing LISA = new Outgoing();
        LISA.setCost(204f);
        LISA.setOutgoingDay(8);
        LISA.setName("Moneybox LISA");
        LISA.setAccount(halifaxFullAccount);
        LISA.setHiddenFromTotal(true);
        this.outgoingRepository.add(LISA);
        //
        Outgoing StocksAndShares = new Outgoing();
        StocksAndShares.setCost(70f);
        StocksAndShares.setOutgoingDay(8);
        StocksAndShares.setName("Moneybox S+S");
        StocksAndShares.setAccount(halifaxFullAccount);
        StocksAndShares.setHiddenFromTotal(true);
        this.outgoingRepository.add(StocksAndShares);
        //
        // Balances
        Balance natwestCreditBalance0 = new Balance();
        natwestCreditBalance0.setAccount(natwestCFullAccount);
        natwestCreditBalance0.setValue(500d);
        natwestCreditBalance0.setTimestamp(generateUnixTimestamp()-86400);
        this.balanceRepository.add(natwestCreditBalance0);
        Balance natwestCreditBalance1 = new Balance();
        natwestCreditBalance1.setAccount(natwestCFullAccount);
        natwestCreditBalance1.setValue(359d);
        natwestCreditBalance1.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(natwestCreditBalance1);
        //
        Balance lloydsBalance = new Balance();
        lloydsBalance.setAccount(lloydsFullAccount);
        lloydsBalance.setValue(2026.49d);
        lloydsBalance.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(lloydsBalance);
        //
        Balance halifaxBalance = new Balance();
        halifaxBalance.setAccount(halifaxFullAccount);
        halifaxBalance.setValue(320.49d);
        halifaxBalance.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(halifaxBalance);
        //
        Balance natwestBalance = new Balance();
        natwestBalance.setAccount(natwestDFullAccount);
        natwestBalance.setValue(353d);
        natwestBalance.setTimestamp(generateUnixTimestamp()-86400);
        this.balanceRepository.add(natwestBalance);
        //
        Balance vanquisBalance = new Balance();
        vanquisBalance.setAccount(vanquisFullAccount);
        vanquisBalance.setValue(799.23d);
        vanquisBalance.setTimestamp(generateUnixTimestamp());
        this.balanceRepository.add(vanquisBalance);
        Balance vanquisBalance1 = new Balance();
        vanquisBalance1.setAccount(vanquisFullAccount);
        vanquisBalance1.setValue(800d);
        vanquisBalance1.setTimestamp(generateUnixTimestamp()-(2*86400));
        this.balanceRepository.add(vanquisBalance1);

        return ok("Seeded");
    }
}
