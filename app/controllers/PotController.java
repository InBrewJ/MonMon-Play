package controllers;

import models.*;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
import static play.libs.Scala.asScala;

public class PotController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final MessagesApi messagesApi;
    private final Form<Pot> form;
    private final PotRepository potRepository;
    private final AccountRepository accountRepository;
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public PotController(
            FormFactory formFactory,
            MessagesApi messagesApi,
            PotRepository potRepository,
            AccountRepository accountRepository,
            IncomingRepository incomingRepository,
            OutgoingRepository outgoingRepository,
            HttpExecutionContext ec
    ) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.potRepository = potRepository;
        this.accountRepository = accountRepository;
        this.incomingRepository = incomingRepository;
        this.outgoingRepository = outgoingRepository;
        this.form = formFactory.form(Pot.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result monthlyPot(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        String region = request.session().get("region").orElse("UK");
        LocalDate today = helpers.BankHolidayHelper.nowInRegion(region);

        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        List<Pot> allCompletePots = repoListToList(potRepository.listComplete(sup.getUserId()));

        List<Pot> activePots = allCompletePots.stream()
                .filter(p -> !p.isArchived())
                .collect(Collectors.toList());

        List<Pot> archivedPots = allCompletePots.stream()
                .filter(Pot::isArchived)
                .sorted(Comparator.comparing((Pot p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L).reversed())
                .collect(Collectors.toList());

        // Calculate surplus (monthly budget)
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        Float incomingTotal = Incoming.getTotalIncomings(incomings);
        Float outgoingTotal = round2(Outgoing.getTotalOutgoingsWithoutHidden(outgoings));
        Float surplus = round2(incomingTotal - outgoingTotal);

        // Find active monthly pot
        Pot activeMonthlyPot = activePots.stream()
                .filter(p -> p.getType() == Pot.PotType.MONTHLY)
                .findFirst()
                .orElse(null);

        int nextPayDay = incomingRepository.getNextPayDay(sup.getUserId());
        int nominalPayday = nextPayDay > 0 ? nextPayDay : 1;

        // Calculate spent this month for the active pot
        Double spentThisMonth = 0.0;
        if (activeMonthlyPot != null) {
            LocalDate cycleStart = helpers.ModelHelpers.findLastPaydayDate(today, nominalPayday);
            java.time.ZoneId zone = helpers.BankHolidayHelper.getTimezone(region);
            long cycleStartSec = cycleStart.atStartOfDay(zone).toEpochSecond();
            long effectiveEndSec = today.atTime(java.time.LocalTime.MAX).atZone(zone).toEpochSecond();

            List<Account> resolvedPotAccounts = helpers.HistoricSpentCalculator.resolvePotAccounts(activeMonthlyPot, accounts);
            for (Account a : resolvedPotAccounts) {
                spentThisMonth += helpers.HistoricSpentCalculator.calculateAccountSpent(a, cycleStartSec, effectiveEndSec);
            }
            spentThisMonth = round2(spentThisMonth);
        }

        return ok(
                views.html.pots.render(
                        asScala(accounts),
                        asScala(activePots),
                        asScala(archivedPots),
                        surplus,
                        spentThisMonth,
                        false,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addPot(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm requestData = formFactory.form().bindFromRequest(request);

        String potTypeStr = requestData.get("type");
        Pot.PotType potType = Pot.PotType.MONTHLY;
        if (potTypeStr != null) {
            try {
                potType = Pot.PotType.valueOf(potTypeStr);
            } catch (Exception ignored) {}
        }

        // Soft-delete previous active monthly pot(s)
        if (potType == Pot.PotType.MONTHLY) {
            potRepository.archiveActiveMonthlyPots(sup.getUserId()).toCompletableFuture().get();
        }

        Pot scratchPot = new Pot();
        scratchPot.setType(potType);
        scratchPot.setCreatedAt(generateUnixTimestamp());
        scratchPot.setLastUpdated(generateUnixTimestamp());
        scratchPot.setUserId(sup.getUserId());

        // Accounts
        Map<String, String[]> formMap = request.body().asFormUrlEncoded();
        String[] accountFormMap = formMap != null ? formMap.get("accounts") : null;
        if (accountFormMap != null) {
            for (String s : accountFormMap) {
                try {
                    Account selectedAccount = accountRepository.findById(parseInt(s)).toCompletableFuture().get();
                    if (selectedAccount != null) {
                        scratchPot.addAccount(selectedAccount);
                    }
                } catch (Exception e) {
                    System.err.println("Error adding account " + s + " to pot: " + e.getMessage());
                }
            }
        }

        // Category account assignments
        Account foodAcc = resolveAccountFromForm(requestData.get("foodAccountId"));
        Account funAcc = resolveAccountFromForm(requestData.get("funAccountId"));
        Account fuelAcc = resolveAccountFromForm(requestData.get("fuelAccountId"));
        Account transportAcc = resolveAccountFromForm(requestData.get("transportAccountId"));

        scratchPot.setFoodAccount(foodAcc);
        scratchPot.setFunAccount(funAcc);
        scratchPot.setFuelAccount(fuelAcc);
        scratchPot.setTransportAccount(transportAcc);

        for (Account ca : java.util.Arrays.asList(foodAcc, funAcc, fuelAcc, transportAcc)) {
            if (ca != null && scratchPot.accounts.stream().noneMatch(a -> a.getId() != null && a.getId().equals(ca.getId()))) {
                scratchPot.addAccount(ca);
            }
        }

        // Spending Plan
        String spendingPlanPreset = requestData.get("spendingPlanPreset"); // "gruelling", "custom", or "none"
        if ("gruelling".equalsIgnoreCase(spendingPlanPreset)) {
            scratchPot.setSpendingPlanName("Gruelling");
            scratchPot.setSpendingPlanFood(viewModels.SpendingPlan.UK_AVG_MONTHLY_FOOD_SPEND);
            scratchPot.setSpendingPlanFuel(0.0f);
            scratchPot.setSpendingPlanTransport(0.0f);
            scratchPot.setSpendingPlanFun(null); // Leftover from allowance
        } else if ("custom".equalsIgnoreCase(spendingPlanPreset)) {
            String customName = requestData.get("customPlanName");
            scratchPot.setSpendingPlanName(customName != null && !customName.trim().isEmpty() ? customName.trim() : "Custom");
            scratchPot.setSpendingPlanFood(parseFloatOrNull(requestData.get("spendingPlanFood")));
            scratchPot.setSpendingPlanFuel(parseFloatOrNull(requestData.get("spendingPlanFuel")));
            scratchPot.setSpendingPlanTransport(parseFloatOrNull(requestData.get("spendingPlanTransport")));
            scratchPot.setSpendingPlanFun(parseFloatOrNull(requestData.get("spendingPlanFun")));
        } else {
            scratchPot.setSpendingPlanName(null);
            scratchPot.setSpendingPlanFood(null);
            scratchPot.setSpendingPlanFuel(null);
            scratchPot.setSpendingPlanTransport(null);
            scratchPot.setSpendingPlanFun(null);
        }

        return potRepository
                .add(scratchPot)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()).flashing("success", "Monthly pot and spending plan configured successfully!"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> applyGruelling(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Pot> activePots = repoListToList(potRepository.list(sup.getUserId()));
        Pot active = activePots.stream().filter(p -> p.getType() == Pot.PotType.MONTHLY).findFirst().orElse(null);

        List<Account> accountsToKeep = active != null && active.accounts != null ? new ArrayList<>(active.accounts) : Collections.emptyList();

        potRepository.archiveActiveMonthlyPots(sup.getUserId()).toCompletableFuture().get();

        Pot scratchPot = new Pot();
        scratchPot.setType(Pot.PotType.MONTHLY);
        scratchPot.setCreatedAt(generateUnixTimestamp());
        scratchPot.setLastUpdated(generateUnixTimestamp());
        scratchPot.setUserId(sup.getUserId());
        scratchPot.setSpendingPlanName("Gruelling");
        scratchPot.setSpendingPlanFood(viewModels.SpendingPlan.UK_AVG_MONTHLY_FOOD_SPEND);
        scratchPot.setSpendingPlanFuel(0.0f);
        scratchPot.setSpendingPlanTransport(0.0f);
        scratchPot.setSpendingPlanFun(null);

        if (active != null) {
            scratchPot.setFoodAccount(active.getFoodAccount());
            scratchPot.setFunAccount(active.getFunAccount());
            scratchPot.setFuelAccount(active.getFuelAccount());
            scratchPot.setTransportAccount(active.getTransportAccount());
        }

        for (Account a : accountsToKeep) {
            scratchPot.addAccount(a);
        }

        return potRepository
                .add(scratchPot)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()).flashing("success", "Gruelling spending plan preset applied!"), ec.current());
    }

    private Account resolveAccountFromForm(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            int id = Integer.parseInt(s.trim());
            if (id <= 0) {
                return null;
            }
            return accountRepository.findById(id).toCompletableFuture().get();
        } catch (Exception e) {
            return null;
        }
    }

    private static Float parseFloatOrNull(String s) {
        if (s == null || s.trim().isEmpty()) {
            return null;
        }
        try {
            return Float.parseFloat(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archivePot(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Archiving pot with id : " + id);
        return potRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()), ec.current());
    }
}
