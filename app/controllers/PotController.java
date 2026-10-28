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
import java.util.concurrent.CompletableFuture;
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
    private final PlanRepository planRepository;
    private final CustomSpendingPlanRepository customSpendingPlanRepository;

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
            PlanRepository planRepository,
            CustomSpendingPlanRepository customSpendingPlanRepository,
            HttpExecutionContext ec
    ) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.potRepository = potRepository;
        this.accountRepository = accountRepository;
        this.incomingRepository = incomingRepository;
        this.outgoingRepository = outgoingRepository;
        this.planRepository = planRepository;
        this.customSpendingPlanRepository = customSpendingPlanRepository;
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

        // Calculate max monthly allowance as set on /spog (taking into account savings goals and bill/rent shares)
        List<Plan> plans = repoListToList(planRepository.list(sup.getUserId()));
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        List<Outgoing> outgoings = repoListToList(outgoingRepository.list(sup.getUserId()));
        Float surplus = helpers.ModelHelpers.calculateMonthlyAllowance(incomings, outgoings, plans, today);

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

        List<CustomSpendingPlan> savedCustomPlans = repoListToList(customSpendingPlanRepository.list(sup.getUserId()));

        return ok(
                views.html.pots.render(
                        asScala(accounts),
                        asScala(activePots),
                        asScala(archivedPots),
                        surplus,
                        spentThisMonth,
                        false,
                        asScala(savedCustomPlans),
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addPot(final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
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
        String spendingPlanPreset = requestData.get("spendingPlanPreset"); // "gruelling", "custom", "saved_<id>", or "none"
        String sectionsJsonPayload = requestData.get("sectionsJsonPayload");
        String customName = requestData.get("customPlanName");
        boolean saveTemplate = "true".equalsIgnoreCase(requestData.get("savePlanTemplate"));

        List<SpendingPlanSection> sections = new ArrayList<>();
        if (sectionsJsonPayload != null && !sectionsJsonPayload.trim().isEmpty()) {
            try {
                sections = play.libs.Json.mapper().readValue(
                        sectionsJsonPayload,
                        new com.fasterxml.jackson.core.type.TypeReference<List<SpendingPlanSection>>() {}
                );
            } catch (Exception ignored) {}
        }

        if ("gruelling".equalsIgnoreCase(spendingPlanPreset)) {
            scratchPot.setSpendingPlanName("Gruelling");
            scratchPot.setSpendingPlanFood(viewModels.SpendingPlan.UK_AVG_MONTHLY_FOOD_SPEND);
            scratchPot.setSpendingPlanFuel(0.0f);
            scratchPot.setSpendingPlanTransport(0.0f);
            scratchPot.setSpendingPlanFun(null); // Leftover from allowance
            List<SpendingPlanSection> gruellingSections = java.util.Collections.singletonList(
                    new SpendingPlanSection("Food", viewModels.SpendingPlan.UK_AVG_MONTHLY_FOOD_SPEND, scratchPot.getFoodAccountId())
            );
            scratchPot.setSpendingSections(gruellingSections);
        } else if ("custom".equalsIgnoreCase(spendingPlanPreset) || (spendingPlanPreset != null && spendingPlanPreset.startsWith("saved_"))) {
            String planName = customName != null && !customName.trim().isEmpty() ? customName.trim() : "Custom";
            scratchPot.setSpendingPlanName(planName);

            if (!sections.isEmpty()) {
                scratchPot.setSpendingSections(sections);
                // Also assign category accounts on pot if matches Food/Fuel/Transport
                for (SpendingPlanSection sec : sections) {
                    if ("food".equalsIgnoreCase(sec.getHeading())) {
                        scratchPot.setSpendingPlanFood(sec.getAmount());
                        if (sec.getAccountId() != null) {
                            try {
                                scratchPot.setFoodAccount(accountRepository.findById(sec.getAccountId().intValue()).toCompletableFuture().get());
                            } catch (Exception ignored) {}
                        }
                    } else if ("fuel".equalsIgnoreCase(sec.getHeading())) {
                        scratchPot.setSpendingPlanFuel(sec.getAmount());
                        if (sec.getAccountId() != null) {
                            try {
                                scratchPot.setFuelAccount(accountRepository.findById(sec.getAccountId().intValue()).toCompletableFuture().get());
                            } catch (Exception ignored) {}
                        }
                    } else if ("transport".equalsIgnoreCase(sec.getHeading())) {
                        scratchPot.setSpendingPlanTransport(sec.getAmount());
                        if (sec.getAccountId() != null) {
                            try {
                                scratchPot.setTransportAccount(accountRepository.findById(sec.getAccountId().intValue()).toCompletableFuture().get());
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } else {
                // Fallback to individual form inputs
                Float food = parseFloatOrNull(requestData.get("spendingPlanFood"));
                Float fuel = parseFloatOrNull(requestData.get("spendingPlanFuel"));
                Float transport = parseFloatOrNull(requestData.get("spendingPlanTransport"));
                Float fun = parseFloatOrNull(requestData.get("spendingPlanFun"));
                scratchPot.setSpendingPlanFood(food);
                scratchPot.setSpendingPlanFuel(fuel);
                scratchPot.setSpendingPlanTransport(transport);
                scratchPot.setSpendingPlanFun(fun);

                List<SpendingPlanSection> legacySecs = new ArrayList<>();
                if (food != null && food > 0f) legacySecs.add(new SpendingPlanSection("Food", food, scratchPot.getFoodAccountId()));
                if (fuel != null && fuel > 0f) legacySecs.add(new SpendingPlanSection("Fuel", fuel, scratchPot.getFuelAccountId()));
                if (transport != null && transport > 0f) legacySecs.add(new SpendingPlanSection("Transport", transport, scratchPot.getTransportAccountId()));
                scratchPot.setSpendingSections(legacySecs);
                sections = legacySecs;
            }

            if (saveTemplate && !sections.isEmpty()) {
                try {
                    customSpendingPlanRepository.createOrUpdate(sup.getUserId(), planName, sections).toCompletableFuture().get();
                } catch (Exception e) {
                    System.err.println("Error saving custom spending plan template: " + e.getMessage());
                }
            }
        } else {
            scratchPot.setSpendingPlanName(null);
            scratchPot.setSpendingPlanFood(null);
            scratchPot.setSpendingPlanFuel(null);
            scratchPot.setSpendingPlanTransport(null);
            scratchPot.setSpendingPlanFun(null);
            scratchPot.setSpendingSections(Collections.emptyList());
        }

        return potRepository
                .add(scratchPot)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()).flashing("success", "Monthly pot and spending plan configured successfully!"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> applyGruelling(final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
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
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        System.out.println("Archiving pot with id : " + id);
        return potRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()), ec.current());
    }
}
