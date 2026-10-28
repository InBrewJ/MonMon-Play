package controllers;

import models.Incoming;
import models.IncomingRepository;
import models.Plan;
import models.PlanRepository;
import models.Pot;
import models.PotRepository;
import models.SavingsPlan;
import models.SavingsPlanRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

public class PlanController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final PlanRepository planRepository;
    private final SavingsPlanRepository savingsPlanRepository;
    private final PotRepository potRepository;
    private final IncomingRepository incomingRepository;
    private MessagesApi messagesApi;
    private final Form<Plan> form;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public PlanController(
            FormFactory formFactory,
            MessagesApi messagesApi,
            PlanRepository planRepository,
            SavingsPlanRepository savingsPlanRepository,
            PotRepository potRepository,
            IncomingRepository incomingRepository,
            HttpExecutionContext ec
    ) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.planRepository = planRepository;
        this.savingsPlanRepository = savingsPlanRepository;
        this.potRepository = potRepository;
        this.incomingRepository = incomingRepository;
        this.form = formFactory.form(Plan.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> getPlans(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return CompletableFuture.completedFuture(unauthorized(play.libs.Json.newObject().put("error", "Unauthorized")));
        }
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        return planRepository
                .list(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> getPlansComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return CompletableFuture.completedFuture(unauthorized(play.libs.Json.newObject().put("error", "Unauthorized")));
        }
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        return planRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> setBasicSavingsPlan(int percent, final Http.Request request) {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        System.out.println("setting savings percentage to : " + percent);
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Plan plan = new Plan();
        plan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        plan.setSplit((float)percent/100);
        plan.setUserId(sup.getUserId());
        plan.setScope(Plan.PlanScope.PERMANENT);
        return planRepository
                .createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, plan)
                .thenComposeAsync(p -> incomingRepository.list(sup.getUserId()), ec.current())
                .thenApplyAsync(incomings -> Incoming.getTotalIncomings(incomings.collect(Collectors.toList())), ec.current())
                .thenComposeAsync(incomingTotal ->
                        savingsPlanRepository.getActive(sup.getUserId()).thenComposeAsync(sp -> {
                            if (sp != null) {
                                return savingsPlanRepository.updatePercentageAndScaleItems(sp.getId(), percent, incomingTotal);
                            } else {
                                SavingsPlan newSp = new SavingsPlan(sup.getUserId(), "Monthly Savings Plan", percent);
                                return savingsPlanRepository.add(newSp);
                            }
                        }, ec.current()), ec.current())
                .thenApplyAsync(p -> redirect(routes.SpogController.index()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addPlan(final Http.Request request) {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Plan plan = formFactory.form(Plan.class).bindFromRequest(request).get();
        java.util.Map<String, String[]> formParams = request.body().asFormUrlEncoded();

        float split = 0.5f;
        String shareMode = formParams != null && formParams.containsKey("shareMode") ? formParams.get("shareMode")[0] : "PEOPLE";

        if ("PERCENT".equalsIgnoreCase(shareMode)) {
            if (formParams != null && formParams.containsKey("splitPercent") && !formParams.get("splitPercent")[0].trim().isEmpty()) {
                float pct = Float.parseFloat(formParams.get("splitPercent")[0].trim());
                split = Math.max(0.01f, Math.min(100.0f, pct)) / 100.0f;
            }
        } else {
            // "PEOPLE" mode: "How many people share"
            float people = 2.0f;
            if (formParams != null && formParams.containsKey("peopleCount") && !formParams.get("peopleCount")[0].trim().isEmpty()) {
                people = Float.parseFloat(formParams.get("peopleCount")[0].trim());
            } else if (formParams != null && formParams.containsKey("humanSplit") && !formParams.get("humanSplit")[0].trim().isEmpty()) {
                people = Float.parseFloat(formParams.get("humanSplit")[0].trim());
            }
            if (people < 1.0f) people = 1.0f;
            split = 1.0f / people;
        }

        plan.setSplit(split);
        plan.setUserId(sup.getUserId());
        if (plan.getScope() == null) {
            plan.setScope(Plan.PlanScope.PERMANENT);
        }

        return planRepository
                .createOrReplace(sup.getUserId(), plan.getType(), plan)
                .thenApplyAsync(p -> redirect(routes.PlanController.sharedOutgoings())
                        .flashing("success", "Sharing agreement for " + (plan.getType() == Plan.PlanType.BILL_SHARE ? "Bills" : "Rent") + " saved successfully!"), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> sharedOutgoings(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return planRepository.list(sup.getUserId()).thenComposeAsync(plansStream -> {
            List<Plan> plans = plansStream.collect(Collectors.toList());
            return savingsPlanRepository.getActive(sup.getUserId()).thenComposeAsync(activeSavingsPlan ->
                potRepository.list(sup.getUserId()).thenApplyAsync(potsStream -> {
                    Pot activeMonthlyPot = potsStream
                            .filter(p -> p != null && p.getType() == Pot.PotType.MONTHLY)
                            .findFirst()
                            .orElse(null);
                    return ok(
                            views.html.plans.render(
                                    asScala(plans),
                                    activeSavingsPlan,
                                    activeMonthlyPot,
                                    this.form,
                                    request,
                                    playSessionStore
                            )
                    );
                }, ec.current())
            , ec.current());
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archivePlan(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        if (!helpers.UserHelpers.isUserApproved(playSessionStore, request)) {
            return CompletableFuture.completedFuture(
                    forbidden(play.libs.Json.newObject().put("error", "Waiting list: Your account is pending administrator approval."))
                            .as("application/json")
            );
        }
        System.out.println("Archiving plan with id : " + id);
        return planRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.PlanController.sharedOutgoings()), ec.current());
    }
}
