package controllers;

import models.Incoming;
import models.Plan;
import models.PlanRepository;
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
    private MessagesApi messagesApi;
    private final Form<Plan> form;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public PlanController(FormFactory formFactory, MessagesApi messagesApi, PlanRepository planRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.planRepository = planRepository;
        this.form = formFactory.form(Plan.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getPlans(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return planRepository
                .list(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getPlansComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return planRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> setBasicSavingsPlan(int percent, final Http.Request request) {
        System.out.println("setting savings percentage to : " + percent);
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Plan plan = new Plan();
        plan.setType(Plan.PlanType.MONTHLY_SAVINGS_GOAL);
        plan.setSplit((float)percent/100);
        plan.setUserId(sup.getUserId());
        plan.setScope(Plan.PlanScope.PERMANENT);
        return planRepository
                .createOrReplace(sup.getUserId(), Plan.PlanType.MONTHLY_SAVINGS_GOAL, plan)
                .thenApplyAsync(p -> redirect(routes.SpogController.index()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addPlan(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Plan plan = formFactory.form(Plan.class).bindFromRequest(request).get();
        Float humanSplitFromForm = Float.parseFloat(request.body().asFormUrlEncoded().get("humanSplit")[0]);
        Float split = (float)1 / (float)humanSplitFromForm;
        System.out.println("Split from number of humans :: " + split);
        plan.setSplit(split);
        plan.setUserId(sup.getUserId());
        return planRepository
                .add(plan)
                .thenApplyAsync(p -> redirect(routes.PlanController.sharedOutgoings()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public Result sharedOutgoings(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Plan> plans = repoListToList(planRepository.list(sup.getUserId()));
        return ok(
                views.html.plans.render(
                        asScala(plans),
                        this.form,
                        request,
                        playSessionStore
                )
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archivePlan(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Archiving plan with id : " + id);
        return planRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.PlanController.sharedOutgoings()), ec.current());
    }
}
