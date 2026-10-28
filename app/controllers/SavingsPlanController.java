package controllers;

import models.Account;
import models.AccountRepository;
import models.Incoming;
import models.IncomingRepository;
import models.Outgoing;
import models.OutgoingRepository;
import models.Plan;
import models.PlanRepository;
import models.SavingsPlan;
import models.SavingsPlanItem;
import models.SavingsPlanRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static models.Incoming.getTotalIncomings;
import static models.Outgoing.getTotalOutgoingsWithoutHidden;
import static play.libs.Scala.asScala;

public class SavingsPlanController extends Controller {

    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final SavingsPlanRepository savingsPlanRepository;
    private final PlanRepository planRepository;
    private final AccountRepository accountRepository;
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public SavingsPlanController(
            FormFactory formFactory,
            HttpExecutionContext ec,
            SavingsPlanRepository savingsPlanRepository,
            PlanRepository planRepository,
            AccountRepository accountRepository,
            IncomingRepository incomingRepository,
            OutgoingRepository outgoingRepository
    ) {
        this.formFactory = formFactory;
        this.ec = ec;
        this.savingsPlanRepository = savingsPlanRepository;
        this.planRepository = planRepository;
        this.accountRepository = accountRepository;
        this.incomingRepository = incomingRepository;
        this.outgoingRepository = outgoingRepository;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return renderSavingsPlanView(sup.getUserId(), request, false, null);
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result editItem(Long itemId, final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        SavingsPlanItem item = savingsPlanRepository.findItemById(itemId).toCompletableFuture().get();
        if (item == null || item.getSavingsPlan() == null || !item.getSavingsPlan().getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        return renderSavingsPlanView(sup.getUserId(), request, true, item);
    }

    private Result renderSavingsPlanView(String userId, Http.Request request, boolean editing, SavingsPlanItem editingItem) throws ExecutionException, InterruptedException {
        List<Account> allAccounts = repoListToList(accountRepository.list(userId));
        Map<Long, Account> accountById = allAccounts.stream()
                .filter(a -> a != null && a.getId() != null)
                .collect(Collectors.toMap(Account::getId, java.util.function.Function.identity(), (a, b) -> a));

        List<SavingsPlan> allPlans = repoListToList(savingsPlanRepository.listComplete(userId));

        SavingsPlan activePlan = allPlans.stream()
                .filter(p -> p != null && !p.isArchived())
                .findFirst()
                .orElse(null);

        // SPOG and Savings Plan are the same thing:
        // If no active SavingsPlan entity exists yet, auto-sync with the savings goal configured on SPOG
        if (activePlan == null) {
            List<Plan> basePlans = repoListToList(planRepository.list(userId));
            Plan spogGoal = basePlans.stream()
                    .filter(p -> p != null && p.getType() == Plan.PlanType.MONTHLY_SAVINGS_GOAL && !p.isArchived())
                    .findFirst()
                    .orElse(null);
            int initialPercent = (spogGoal != null && spogGoal.getSplit() != null) ? (int) (spogGoal.getSplit() * 100) : 0;
            if (allPlans.isEmpty() || initialPercent > 0) {
                SavingsPlan newPlan = new SavingsPlan(userId, "Monthly Savings Plan", initialPercent);
                activePlan = savingsPlanRepository.add(newPlan).toCompletableFuture().get();
            }
        }

        if (activePlan != null && activePlan.getItems() != null) {
            for (SavingsPlanItem item : activePlan.getItems()) {
                if (item.getAccount() != null && item.getAccount().getId() != null) {
                    Account resolved = accountById.get(item.getAccount().getId());
                    if (resolved != null) {
                        item.setAccount(resolved);
                    }
                }
            }
        }

        if (editingItem != null && editingItem.getAccount() != null && editingItem.getAccount().getId() != null) {
            Account resolved = accountById.get(editingItem.getAccount().getId());
            if (resolved != null) {
                editingItem.setAccount(resolved);
            }
        }

        List<SavingsPlan> previousPlans = allPlans.stream()
                .filter(p -> p != null && p.isArchived())
                .sorted(Comparator.comparing((SavingsPlan p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L).reversed())
                .collect(Collectors.toList());

        List<Incoming> incomings = repoListToList(incomingRepository.list(userId));
        Float incomingTotal = getTotalIncomings(incomings);

        // Calculate maximum monthly savings allowed by SPOG surplus
        List<Outgoing> allOutgoings = repoListToList(outgoingRepository.list(userId));
        List<Plan> basePlans = repoListToList(planRepository.list(userId));
        Plan firstBillShare = basePlans.stream().filter(p -> p != null && p.getType() == Plan.PlanType.BILL_SHARE && !p.isArchived()).findFirst().orElse(null);
        Plan firstRentShare = basePlans.stream().filter(p -> p != null && p.getType() == Plan.PlanType.RENT_SHARE && !p.isArchived()).findFirst().orElse(null);

        for (Outgoing o : allOutgoings) {
            if (firstBillShare != null && o.isBill()) {
                o.setCost(o.getCost() * firstBillShare.getSplit());
            }
            if (firstRentShare != null && o.isRent()) {
                o.setCost(o.getCost() * firstRentShare.getSplit());
            }
        }
        String region = request.session().get("region").orElse("UK");
        java.time.LocalDate today = helpers.BankHolidayHelper.nowInRegion(region);
        Float outgoingTotal = round2(getTotalOutgoingsWithoutHidden(allOutgoings, today));
        Float maxSurplus = round2(Math.max(0.0f, incomingTotal - outgoingTotal));
        int maxPercent = incomingTotal > 0 ? Math.min(100, Math.max(0, (int) Math.floor((maxSurplus / incomingTotal) * 100))) : 100;

        int nextPayDay = incomingRepository.getNextPayDay(userId);
        java.time.LocalDate lastPayDate = helpers.ModelHelpers.findLastPaydayDate(today, nextPayDay);
        java.time.LocalDate nextPayDate = helpers.ModelHelpers.findNextPaydayDate(today, nextPayDay);
        int daysBetweenPaydays = (int) java.time.temporal.ChronoUnit.DAYS.between(lastPayDate, nextPayDate);
        if (daysBetweenPaydays <= 0) daysBetweenPaydays = 30;

        return ok(views.html.savingsPlan.render(
                activePlan,
                asScala(previousPlans),
                asScala(allAccounts),
                incomingTotal,
                maxSurplus,
                maxPercent,
                editing,
                editingItem,
                daysBetweenPaydays,
                request,
                playSessionStore
        ));
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> createOrUpdatePlan(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm requestData = formFactory.form().bindFromRequest(request);

        String name = requestData.get("name");
        String percentStr = requestData.get("savingsPercentage");
        int percent = 0;
        try {
            if (percentStr != null && !percentStr.trim().isEmpty()) {
                percent = Integer.parseInt(percentStr.trim());
            }
        } catch (NumberFormatException ignored) {}

        SavingsPlan plan = new SavingsPlan(sup.getUserId(), name, percent);

        // Keep Plan.PlanType.MONTHLY_SAVINGS_GOAL in sync
        Plan basePlan = new Plan();
        basePlan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        basePlan.setSplit((float) percent / 100.0f);
        basePlan.setUserId(sup.getUserId());
        basePlan.setScope(Plan.PlanScope.PERMANENT);

        return planRepository.createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, basePlan)
                .thenComposeAsync(bp -> savingsPlanRepository.createOrReplace(sup.getUserId(), plan), ec.current())
                .thenApplyAsync(p -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updatePercentage(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm requestData = formFactory.form().bindFromRequest(request);

        Long planId = Long.parseLong(requestData.get("planId"));
        int percent = Integer.parseInt(requestData.get("savingsPercentage"));

        Plan basePlan = new Plan();
        basePlan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        basePlan.setSplit((float) percent / 100.0f);
        basePlan.setUserId(sup.getUserId());
        basePlan.setScope(Plan.PlanScope.PERMANENT);

        return planRepository.createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, basePlan)
                .thenComposeAsync(bp -> incomingRepository.list(sup.getUserId()), ec.current())
                .thenApplyAsync(incomings -> Incoming.getTotalIncomings(incomings.collect(Collectors.toList())), ec.current())
                .thenComposeAsync(incomingTotal -> savingsPlanRepository.updatePercentageAndScaleItems(planId, percent, incomingTotal), ec.current())
                .thenApplyAsync(p -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addItem(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm requestData = formFactory.form().bindFromRequest(request);

        Long planId = Long.parseLong(requestData.get("planId"));
        String name = requestData.get("name");
        String typeStr = requestData.get("type");
        SavingsPlanItem.ItemType type = "DEBT_PAYOFF".equalsIgnoreCase(typeStr)
                ? SavingsPlanItem.ItemType.DEBT_PAYOFF
                : SavingsPlanItem.ItemType.SAVINGS_TARGET;

        String accountIdStr = requestData.get("accountId");
        Account account = null;
        if (accountIdStr != null && !accountIdStr.trim().isEmpty() && !accountIdStr.equals("0")) {
            try {
                int accId = Integer.parseInt(accountIdStr.trim());
                account = accountRepository.findById(accId).toCompletableFuture().get();
            } catch (Exception ignored) {}
        }

        Float targetAmount = 0.0f;
        String targetStr = requestData.get("targetAmount");
        if (targetStr != null && !targetStr.trim().isEmpty()) {
            try {
                targetAmount = Float.parseFloat(targetStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Float monthlyAmount = 0.0f;
        String monthlyStr = requestData.get("monthlyAmount");
        if (monthlyStr != null && !monthlyStr.trim().isEmpty()) {
            try {
                monthlyAmount = Float.parseFloat(monthlyStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Integer priority = null;
        String priorityStr = requestData.get("priority");
        if (priorityStr != null && !priorityStr.trim().isEmpty()) {
            try {
                priority = Integer.parseInt(priorityStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        SavingsPlanItem item = new SavingsPlanItem(name, type, account, targetAmount, monthlyAmount, priority);

        return savingsPlanRepository.addItem(planId, item)
                .thenApplyAsync(i -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateItem(Long itemId, final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm requestData = formFactory.form().bindFromRequest(request);

        String name = requestData.get("name");
        String typeStr = requestData.get("type");
        SavingsPlanItem.ItemType type = "DEBT_PAYOFF".equalsIgnoreCase(typeStr)
                ? SavingsPlanItem.ItemType.DEBT_PAYOFF
                : SavingsPlanItem.ItemType.SAVINGS_TARGET;

        Long accountId = null;
        String accountIdStr = requestData.get("accountId");
        if (accountIdStr != null && !accountIdStr.trim().isEmpty() && !accountIdStr.equals("0")) {
            try {
                accountId = Long.parseLong(accountIdStr.trim());
            } catch (Exception ignored) {}
        }

        Float targetAmount = 0.0f;
        String targetStr = requestData.get("targetAmount");
        if (targetStr != null && !targetStr.trim().isEmpty()) {
            try {
                targetAmount = Float.parseFloat(targetStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Float monthlyAmount = 0.0f;
        String monthlyStr = requestData.get("monthlyAmount");
        if (monthlyStr != null && !monthlyStr.trim().isEmpty()) {
            try {
                monthlyAmount = Float.parseFloat(monthlyStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        Integer priority = 1;
        String priorityStr = requestData.get("priority");
        if (priorityStr != null && !priorityStr.trim().isEmpty()) {
            try {
                priority = Integer.parseInt(priorityStr.trim());
            } catch (NumberFormatException ignored) {}
        }

        return savingsPlanRepository.updateItem(itemId, name, type, accountId, targetAmount, monthlyAmount, priority)
                .thenApplyAsync(i -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result addAllOverdrafts(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        String userId = sup.getUserId();

        SavingsPlan activePlan = savingsPlanRepository.getActive(userId).toCompletableFuture().get();
        if (activePlan == null) {
            SavingsPlan newPlan = new SavingsPlan(userId, "Monthly Savings Plan", 0);
            activePlan = savingsPlanRepository.add(newPlan).toCompletableFuture().get();
        }

        List<Account> allAccounts = repoListToList(accountRepository.list(userId));
        java.util.Set<Long> alreadyLinkedAccountIds = activePlan.getItems().stream()
                .filter(i -> i.getAccount() != null && i.getAccount().getId() != null)
                .map(i -> i.getAccount().getId())
                .collect(Collectors.toSet());

        // Find debit accounts with negative balance (overdrawn)
        List<Account> overdrawn = allAccounts.stream()
                .filter(a -> a != null && (a.getType() == Account.AccountType.DEBIT || a.getType() == Account.AccountType.DEBIT_SHARED_BILLS))
                .filter(a -> a.getLatestBalanceValue() != null && a.getLatestBalanceValue() < 0f)
                .filter(a -> !alreadyLinkedAccountIds.contains(a.getId()))
                .collect(Collectors.toList());

        // If none currently negative, also check debit accounts with configured overdraft limits
        if (overdrawn.isEmpty()) {
            overdrawn = allAccounts.stream()
                    .filter(a -> a != null && (a.getType() == Account.AccountType.DEBIT || a.getType() == Account.AccountType.DEBIT_SHARED_BILLS))
                    .filter(a -> a.getAvailableLimit() != null && a.getAvailableLimit() > 0f)
                    .filter(a -> !alreadyLinkedAccountIds.contains(a.getId()))
                    .collect(Collectors.toList());
        }

        int priority = activePlan.getItems().size() + 1;
        for (Account acc : overdrawn) {
            float overdraftDebt = acc.getLatestBalanceValue() < 0f
                    ? Math.abs(acc.getLatestBalanceValue())
                    : (acc.getAvailableLimit() != null ? acc.getAvailableLimit() : 0f);
            SavingsPlanItem item = new SavingsPlanItem(
                    acc.getName() + " Overdraft",
                    SavingsPlanItem.ItemType.DEBT_PAYOFF,
                    acc,
                    overdraftDebt,
                    0.0f,
                    priority++
            );
            savingsPlanRepository.addItem(activePlan.getId(), item).toCompletableFuture().get();
        }

        return redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card");
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result addAllCreditCards(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        String userId = sup.getUserId();

        SavingsPlan activePlan = savingsPlanRepository.getActive(userId).toCompletableFuture().get();
        if (activePlan == null) {
            SavingsPlan newPlan = new SavingsPlan(userId, "Monthly Savings Plan", 0);
            activePlan = savingsPlanRepository.add(newPlan).toCompletableFuture().get();
        }

        List<Account> allAccounts = repoListToList(accountRepository.list(userId));
        java.util.Set<Long> alreadyLinkedAccountIds = activePlan.getItems().stream()
                .filter(i -> i.getAccount() != null && i.getAccount().getId() != null)
                .map(i -> i.getAccount().getId())
                .collect(Collectors.toSet());

        // Find credit accounts that have an outstanding balance and are not already in the plan
        List<Account> creditCardsWithBalance = allAccounts.stream()
                .filter(a -> a != null && a.getType() == Account.AccountType.CREDIT)
                .filter(a -> !alreadyLinkedAccountIds.contains(a.getId()))
                .filter(a -> {
                    float limit = a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f;
                    float balance = a.getLatestBalanceValue() != null ? a.getLatestBalanceValue() : 0f;
                    float debt = Math.max(0f, limit - balance);
                    return debt > 0f || (limit == 0f && balance > 0f);
                })
                .collect(Collectors.toList());

        // Append to the priority list with a priority lower than anything already in the list
        int priority = activePlan.getItems().size() + 1;
        for (Account acc : creditCardsWithBalance) {
            float limit = acc.getAvailableLimit() != null ? acc.getAvailableLimit() : 0f;
            float balance = acc.getLatestBalanceValue() != null ? acc.getLatestBalanceValue() : 0f;
            float debt = Math.max(0f, limit - balance);
            if (debt <= 0f && limit == 0f && balance > 0f) {
                debt = balance;
            }

            SavingsPlanItem item = new SavingsPlanItem(
                    acc.getName(),
                    SavingsPlanItem.ItemType.DEBT_PAYOFF,
                    acc,
                    debt,
                    0.0f,
                    priority++
            );
            savingsPlanRepository.addItem(activePlan.getId(), item).toCompletableFuture().get();
        }

        return redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card");
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveItem(Long id, final Http.Request request) {
        return savingsPlanRepository.archiveItem(id)
                .thenApplyAsync(b -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> moveItemUp(Long id, final Http.Request request) {
        return savingsPlanRepository.moveItemUp(id)
                .thenApplyAsync(b -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> moveItemDown(Long id, final Http.Request request) {
        return savingsPlanRepository.moveItemDown(id)
                .thenApplyAsync(b -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> toggleLock(Long id, final Http.Request request) {
        return savingsPlanRepository.toggleLock(id)
                .thenApplyAsync(i -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> balanceEvenly(Long id, final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return incomingRepository.list(sup.getUserId())
                .thenApplyAsync(incomings -> Incoming.getTotalIncomings(incomings.collect(Collectors.toList())), ec.current())
                .thenComposeAsync(total -> savingsPlanRepository.balanceEvenly(id, total), ec.current())
                .thenApplyAsync(sp -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> saveAllocations(Long id, final Http.Request request) {
        DynamicForm requestData = formFactory.form().bindFromRequest(request);
        Map<String, String> rawData = requestData.rawData();
        Map<Long, Float> amounts = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : rawData.entrySet()) {
            if (entry.getKey().startsWith("amount_")) {
                try {
                    Long itemId = Long.parseLong(entry.getKey().substring("amount_".length()));
                    Float val = Float.parseFloat(entry.getValue());
                    amounts.put(itemId, val);
                } catch (Exception ignored) {}
            }
        }
        return savingsPlanRepository.updateItemAmounts(id, amounts)
                .thenApplyAsync(sp -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archivePlan(Long id, final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);

        Plan zeroPlan = new Plan();
        zeroPlan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        zeroPlan.setSplit(0.0f);
        zeroPlan.setUserId(sup.getUserId());
        zeroPlan.setScope(Plan.PlanScope.PERMANENT);

        return planRepository.createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, zeroPlan)
                .thenComposeAsync(p -> savingsPlanRepository.archive(id), ec.current())
                .thenApplyAsync(sp -> redirect(routes.SavingsPlanController.index().url() + "#sliced-items-card"), ec.current());
    }
}
