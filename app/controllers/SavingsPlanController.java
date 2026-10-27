package controllers;

import models.*;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static models.Incoming.getTotalIncomings;
import static play.libs.Scala.asScala;

public class SavingsPlanController extends Controller {

    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final SavingsPlanRepository savingsPlanRepository;
    private final PlanRepository planRepository;
    private final AccountRepository accountRepository;
    private final IncomingRepository incomingRepository;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public SavingsPlanController(
            FormFactory formFactory,
            HttpExecutionContext ec,
            SavingsPlanRepository savingsPlanRepository,
            PlanRepository planRepository,
            AccountRepository accountRepository,
            IncomingRepository incomingRepository
    ) {
        this.formFactory = formFactory;
        this.ec = ec;
        this.savingsPlanRepository = savingsPlanRepository;
        this.planRepository = planRepository;
        this.accountRepository = accountRepository;
        this.incomingRepository = incomingRepository;
    }

    @Secure(clients = "OidcClient")
    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        String userId = sup.getUserId();

        List<Account> allAccounts = repoListToList(accountRepository.list(userId));
        Map<Long, Account> accountById = allAccounts.stream()
                .filter(a -> a != null && a.getId() != null)
                .collect(Collectors.toMap(Account::getId, java.util.function.Function.identity(), (a, b) -> a));

        List<SavingsPlan> allPlans = repoListToList(savingsPlanRepository.listComplete(userId));

        SavingsPlan activePlan = allPlans.stream()
                .filter(p -> p != null && !p.isArchived())
                .findFirst()
                .orElse(null);

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

        List<SavingsPlan> previousPlans = allPlans.stream()
                .filter(p -> p != null && p.isArchived())
                .sorted(Comparator.comparing((SavingsPlan p) -> p.getCreatedAt() != null ? p.getCreatedAt() : 0L).reversed())
                .collect(Collectors.toList());

        List<Incoming> incomings = repoListToList(incomingRepository.list(userId));
        Float incomingTotal = getTotalIncomings(incomings);

        return ok(views.html.savingsPlan.render(
                activePlan,
                asScala(previousPlans),
                asScala(allAccounts),
                incomingTotal,
                request,
                playSessionStore
        ));
    }

    @Secure(clients = "OidcClient")
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
                .thenApplyAsync(p -> redirect(routes.SavingsPlanController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
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
                .thenComposeAsync(bp -> savingsPlanRepository.updatePercentage(planId, percent), ec.current())
                .thenApplyAsync(p -> redirect(routes.SavingsPlanController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
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

        SavingsPlanItem item = new SavingsPlanItem(name, type, account, targetAmount, monthlyAmount);

        return savingsPlanRepository.addItem(planId, item)
                .thenApplyAsync(i -> redirect(routes.SavingsPlanController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> archiveItem(Long id, final Http.Request request) {
        return savingsPlanRepository.archiveItem(id)
                .thenApplyAsync(b -> redirect(routes.SavingsPlanController.index()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> archivePlan(Long id, final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);

        // Also reset/archive the MONTHLY_SAVINGS_GOAL plan
        Plan zeroPlan = new Plan();
        zeroPlan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        zeroPlan.setSplit(0.0f);
        zeroPlan.setUserId(sup.getUserId());
        zeroPlan.setScope(Plan.PlanScope.PERMANENT);

        return planRepository.createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, zeroPlan)
                .thenComposeAsync(p -> savingsPlanRepository.archive(id), ec.current())
                .thenApplyAsync(sp -> redirect(routes.SavingsPlanController.index()), ec.current());
    }
}
