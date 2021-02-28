package controllers;

import helpers.UserHelpers;
import models.*;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.exception.TechnicalException;
import org.pac4j.core.exception.http.HttpAction;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.core.util.Pac4jConstants;
import org.pac4j.play.PlayWebContext;
import org.pac4j.play.http.PlayHttpActionAdapter;
import org.pac4j.play.java.Secure;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.*;
import viewModels.SimpleUserProfile;
import viewModels.Spog;

import javax.inject.Inject;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import org.pac4j.core.client.Client;
import org.pac4j.core.config.Config;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static helpers.UserHelpers.getAuthProfiles;
import static helpers.UserHelpers.getSimpleUserProfile;
import static models.Incoming.getTotalIncomings;
import static models.Outgoing.getTotalOutgoingsWithoutHidden;

public class SpogController extends Controller {
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;
    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final PlanRepository planRepository;
    private final HttpExecutionContext ec;

    @Inject
    public SpogController(
            PlanRepository planRepository,
            OutgoingRepository outgoingRepository,
            AccountRepository accountRepository,
            IncomingRepository incomingRepository,
            BalanceRepository balanceRepository,
            HttpExecutionContext ec) {
        this.incomingRepository = incomingRepository;
        this.accountRepository = accountRepository;
        this.outgoingRepository = outgoingRepository;
        this.balanceRepository = balanceRepository;
        this.planRepository = planRepository;
        this.ec = ec;
    }

    @Inject
    private Config config;

    @Inject
    private SessionStore playSessionStore;

    @Secure
    public Result protectedIndex(Http.Request request) {
        return protectedIndexView(request);
    }

    public Result forceLogin(Http.Request request) {
        final PlayWebContext context = new PlayWebContext(request);
        final Client client = config.getClients().findClient(context.getRequestParameter(Pac4jConstants.DEFAULT_CLIENT_NAME_PARAMETER).get()).get();
        try {
            final HttpAction action = client.getRedirectionAction(context, playSessionStore).get();
            return PlayHttpActionAdapter.INSTANCE.adapt(action, context);
        } catch (final HttpAction e) {
            throw new TechnicalException(e);
        }
    }

    private Result protectedIndexView(Http.Request request) {
        // profiles
        return ok(views.html.protectedIndex.render(getAuthProfiles(playSessionStore, request)));
    }

    @Secure(clients = "OidcClient")
    public Result oidcIndex(Http.Request request) {
        return protectedIndexView(request);
    }

    @Secure(clients = "OidcClient")
    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        // Plans affect how total outgoings and rent values appear
        List<Plan> allPlans = repoListToList(planRepository.list());
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Plan firstRentShare = null;
        Plan firstBillShare = null;
        try {
            firstRentShare = !allPlans.isEmpty() ?
                    allPlans
                            .stream()
                            .filter(p -> p.getType() == Plan.PlanType.RENT_SHARE)
                            .collect(Collectors.toList())
                            .get(0) : null;
        } catch (Exception e) {
            System.out.println("No RENT_SHARE found");
        }

        try {
            firstBillShare = !allPlans.isEmpty() ?
                    allPlans
                            .stream()
                            .filter(p -> p.getType() == Plan.PlanType.BILL_SHARE)
                            .collect(Collectors.toList())
                            .get(0) : null;
        } catch (Exception e) {
            System.out.println("No BILL_SHARE found");
        }

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

        List<Account> allAccounts = repoListToList(this.accountRepository.list(sup.getUserId()));
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

    // Ideally, seed should only be possible by someone
    // with a well defined role. Maybe an admin/seeder
    @Secure(clients = "OidcClient")
    public Result seed(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
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
        natwestC.setUserId(sup.getUserId());
        natwestCFullAccount = this.accountRepository.add(natwestC).toCompletableFuture().get();
        long natwestCreditId = natwestCFullAccount.getId();
        //
        Account natwestD = new Account();
        Account natwestDFullAccount;
        natwestD.setName("Natwest Debit");
        natwestD.setType(Account.AccountType.DEBIT);
        natwestD.setNickname("main overflow");
        natwestD.setUserId(sup.getUserId());
        natwestDFullAccount = this.accountRepository.add(natwestD).toCompletableFuture().get();
        long natwestDebitId = natwestDFullAccount.getId();
        //
        Account vanquis = new Account();
        Account vanquisFullAccount;
        vanquis.setName("Vanquis");
        vanquis.setType(Account.AccountType.CREDIT);
        vanquis.setNickname("The builder");
        vanquis.setUserId(sup.getUserId());
        vanquisFullAccount = this.accountRepository.add(vanquis).toCompletableFuture().get();
        //
        Account lloyds = new Account();
        Account lloydsFullAccount;
        lloyds.setName("Lloyds");
        lloyds.setType(Account.AccountType.DEBIT_SHARED_BILLS);
        lloyds.setUserId(sup.getUserId());
        natwestD.setNickname("salary in / bill account");
        lloydsFullAccount = this.accountRepository.add(lloyds).toCompletableFuture().get();
        //
        Account halifax = new Account();
        Account halifaxFullAccount;
        halifax.setName("Halifax");
        halifax.setType(Account.AccountType.DEBIT);
        halifax.setNickname("daily driver");
        halifax.setUserId(sup.getUserId());
        halifaxFullAccount = this.accountRepository.add(halifax).toCompletableFuture().get();
        // Incomings
        Incoming salary = new Incoming();
        salary.setName("ovo");
        salary.setNetValue(2839.04f);
        salary.setPayDay(true);
        salary.setIncomingMonthDay(26);
        salary.setType("salary");
        salary.setUserId(sup.getUserId());
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
        rent.setUserId(sup.getUserId());
        this.outgoingRepository.add(rent);
        //
        Outgoing virgin = new Outgoing();
        virgin.setBill(true);
        virgin.setCost(27f);
        virgin.setOutgoingDay(3);
        virgin.setName("Virgin media");
        virgin.setAccount(lloydsFullAccount);
        virgin.setUserId(sup.getUserId());
        this.outgoingRepository.add(virgin);
        //
        Outgoing water = new Outgoing();
        water.setBill(true);
        water.setCost(28.62f);
        water.setOutgoingDay(1);
        water.setName("Water bill");
        water.setAccount(lloydsFullAccount);
        water.setUserId(sup.getUserId());
        this.outgoingRepository.add(water);
        //
        Outgoing councilTax = new Outgoing();
        councilTax.setBill(true);
        councilTax.setCost(149f);
        councilTax.setOutgoingDay(1);
        councilTax.setName("Council tax");
        councilTax.setAccount(lloydsFullAccount);
        councilTax.setUserId(sup.getUserId());
        this.outgoingRepository.add(councilTax);
        //
        Outgoing ovoEnergy = new Outgoing();
        ovoEnergy.setBill(true);
        ovoEnergy.setCost(75f);
        ovoEnergy.setOutgoingDay(1);
        ovoEnergy.setName("Ovo Gaz e Luce");
        ovoEnergy.setAccount(lloydsFullAccount);
        ovoEnergy.setUserId(sup.getUserId());
        this.outgoingRepository.add(ovoEnergy);
        // Rest of outgoings
        Outgoing audible = new Outgoing();
        audible.setCost(7.99f);
        audible.setOutgoingDay(10);
        audible.setName("Audible");
        audible.setAccount(natwestCFullAccount);
        audible.setUserId(sup.getUserId());
        this.outgoingRepository.add(audible);
        //
        Outgoing yousician = new Outgoing();
        yousician.setCost(12.99f);
        yousician.setOutgoingDay(13);
        yousician.setName("Yousician");
        yousician.setAccount(natwestCFullAccount);
        yousician.setUserId(sup.getUserId());
        this.outgoingRepository.add(yousician);
        //
        Outgoing savingsPayoff = new Outgoing();
        savingsPayoff.setCost(1305.96f);
        savingsPayoff.setOutgoingDay(26);
        savingsPayoff.setName("Savings/Payoff");
        savingsPayoff.setAccount(lloydsFullAccount);
        savingsPayoff.setUserId(sup.getUserId());
        this.outgoingRepository.add(savingsPayoff);
        //
        Outgoing natwestCreditPayoff = new Outgoing();
        natwestCreditPayoff.setCost(13.92f);
        natwestCreditPayoff.setOutgoingDay(15);
        natwestCreditPayoff.setName("Natwest Credit Card");
        natwestCreditPayoff.setAccount(natwestDFullAccount);
        natwestCreditPayoff.setUserId(sup.getUserId());
        this.outgoingRepository.add(natwestCreditPayoff);
        //
        Outgoing MSOffice = new Outgoing();
        MSOffice.setCost(9.48f);
        MSOffice.setOutgoingDay(10);
        MSOffice.setName("MS Office (gps)");
        MSOffice.setAccount(natwestCFullAccount);
        MSOffice.setUserId(sup.getUserId());
        this.outgoingRepository.add(MSOffice);
        //
        Outgoing o2bill = new Outgoing();
        o2bill.setCost(57.46f);
        o2bill.setOutgoingDay(18);
        o2bill.setName("O2 bill");
        o2bill.setAccount(natwestDFullAccount);
        o2bill.setUserId(sup.getUserId());
        this.outgoingRepository.add(o2bill);
        //
        Outgoing spotify = new Outgoing();
        spotify.setCost(9.99f);
        spotify.setOutgoingDay(18);
        spotify.setName("Spotify");
        spotify.setAccount(natwestCFullAccount);
        spotify.setUserId(sup.getUserId());
        this.outgoingRepository.add(spotify);
        //
        Outgoing netflix = new Outgoing();
        netflix.setCost(9.99f);
        netflix.setOutgoingDay(21);
        netflix.setName("Netflix");
        netflix.setAccount(natwestCFullAccount);
        netflix.setUserId(sup.getUserId());
        this.outgoingRepository.add(netflix);
        //
        Outgoing medium = new Outgoing();
        medium.setCost(3.87f);
        medium.setOutgoingDay(21);
        medium.setName("Medium");
        medium.setAccount(natwestCFullAccount);
        medium.setUserId(sup.getUserId());
        this.outgoingRepository.add(medium);
        //
        Outgoing nuranow = new Outgoing();
        nuranow.setCost(9.99f);
        nuranow.setOutgoingDay(28);
        nuranow.setName("Nuraphones");
        nuranow.setAccount(natwestCFullAccount);
        nuranow.setUserId(sup.getUserId());
        this.outgoingRepository.add(nuranow);
        //
        Outgoing urawizardSite = new Outgoing();
        urawizardSite.setCost(6f);
        urawizardSite.setOutgoingDay(6);
        urawizardSite.setName("urawizard.com");
        urawizardSite.setAccount(natwestCFullAccount);
        urawizardSite.setUserId(sup.getUserId());
        this.outgoingRepository.add(urawizardSite);
        //
        Outgoing guardian = new Outgoing();
        guardian.setCost(11.99f);
        guardian.setOutgoingDay(7);
        guardian.setName("Guardian w/Crosswords");
        guardian.setAccount(natwestCFullAccount);
        guardian.setUserId(sup.getUserId());
        this.outgoingRepository.add(guardian);
        //
        Outgoing fitbit = new Outgoing();
        fitbit.setCost(7.99f);
        fitbit.setOutgoingDay(1);
        fitbit.setName("Fitbit premium");
        fitbit.setAccount(halifaxFullAccount);
        fitbit.setUserId(sup.getUserId());
        this.outgoingRepository.add(fitbit);
        //
        Outgoing LISA = new Outgoing();
        LISA.setCost(204f);
        LISA.setOutgoingDay(8);
        LISA.setName("Moneybox LISA");
        LISA.setAccount(halifaxFullAccount);
        LISA.setHiddenFromTotal(true);
        LISA.setUserId(sup.getUserId());
        this.outgoingRepository.add(LISA);
        //
        Outgoing StocksAndShares = new Outgoing();
        StocksAndShares.setCost(70f);
        StocksAndShares.setOutgoingDay(8);
        StocksAndShares.setName("Moneybox S+S");
        StocksAndShares.setAccount(halifaxFullAccount);
        StocksAndShares.setHiddenFromTotal(true);
        StocksAndShares.setUserId(sup.getUserId());
        this.outgoingRepository.add(StocksAndShares);
        //
        // Balances
        Balance natwestCreditBalance0 = new Balance();
        natwestCreditBalance0.setAccount(natwestCFullAccount);
        natwestCreditBalance0.setValue(500d);
        natwestCreditBalance0.setTimestamp(generateUnixTimestamp()-86400);
        natwestCreditBalance0.setUserId(sup.getUserId());
        this.balanceRepository.add(natwestCreditBalance0);
        Balance natwestCreditBalance1 = new Balance();
        natwestCreditBalance1.setAccount(natwestCFullAccount);
        natwestCreditBalance1.setValue(359d);
        natwestCreditBalance1.setTimestamp(generateUnixTimestamp());
        natwestCreditBalance1.setUserId(sup.getUserId());
        this.balanceRepository.add(natwestCreditBalance1);
        //
        Balance lloydsBalance = new Balance();
        lloydsBalance.setAccount(lloydsFullAccount);
        lloydsBalance.setValue(2026.49d);
        lloydsBalance.setTimestamp(generateUnixTimestamp());
        lloydsBalance.setUserId(sup.getUserId());
        this.balanceRepository.add(lloydsBalance);
        //
        Balance halifaxBalance = new Balance();
        halifaxBalance.setAccount(halifaxFullAccount);
        halifaxBalance.setValue(320.49d);
        halifaxBalance.setTimestamp(generateUnixTimestamp());
        halifax.setUserId(sup.getUserId());
        this.balanceRepository.add(halifaxBalance);
        //
        Balance natwestBalance = new Balance();
        natwestBalance.setAccount(natwestDFullAccount);
        natwestBalance.setValue(353d);
        natwestBalance.setTimestamp(generateUnixTimestamp()-86400);
        natwestBalance.setUserId(sup.getUserId());
        this.balanceRepository.add(natwestBalance);
        //
        Balance vanquisBalance = new Balance();
        vanquisBalance.setAccount(vanquisFullAccount);
        vanquisBalance.setValue(799.23d);
        vanquisBalance.setTimestamp(generateUnixTimestamp());
        vanquisBalance.setUserId(sup.getUserId());
        this.balanceRepository.add(vanquisBalance);
        Balance vanquisBalance1 = new Balance();
        vanquisBalance1.setAccount(vanquisFullAccount);
        vanquisBalance1.setValue(800d);
        vanquisBalance1.setTimestamp(generateUnixTimestamp()-(2*86400));
        vanquisBalance1.setUserId(sup.getUserId());
        this.balanceRepository.add(vanquisBalance1);
        // Plans
        Plan rentSharePlan = new Plan();
        rentSharePlan.setSplit(0.5f);
        rentSharePlan.setType(Plan.PlanType.RENT_SHARE);
        rentSharePlan.setScope(Plan.PlanScope.PERMANENT);
        rentSharePlan.setUserId(sup.getUserId());
        this.planRepository.add(rentSharePlan);
        Plan billSharePlan = new Plan();
        billSharePlan.setSplit(0.5f);
        billSharePlan.setType(Plan.PlanType.BILL_SHARE);
        billSharePlan.setScope(Plan.PlanScope.PERMANENT);
        billSharePlan.setUserId(sup.getUserId());
        this.planRepository.add(billSharePlan);

        return ok("Seeded");
    }
}
