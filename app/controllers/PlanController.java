package controllers;

import models.Incoming;
import models.Plan;
import models.PlanRepository;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
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
    public PlanController(FormFactory formFactory, MessagesApi messagesApi, PlanRepository planRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.planRepository = planRepository;
        this.form = formFactory.form(Plan.class);
        this.ec = ec;
    }

    public CompletionStage<Result> getPlans() {
        return planRepository
                .list()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    public CompletionStage<Result> addPlan(final Http.Request request) {
        Plan plan = formFactory.form(Plan.class).bindFromRequest(request).get();
        int humanSplitFromForm = parseInt(request.body().asFormUrlEncoded().get("humanSplit")[0]);
        Float split = (float)1 / (float)humanSplitFromForm;
        System.out.println("Split from number of humans :: " + split);
        plan.setSplit(split);
        return planRepository
                .add(plan)
                .thenApplyAsync(p -> redirect(routes.PlanController.sharedOutgoings()), ec.current());
    }

    public Result sharedOutgoings(final Http.Request request) throws ExecutionException, InterruptedException {
        List<Plan> plans = repoListToList(planRepository.list());
        return ok(views.html.sharing.render(asScala(plans), this.form, request));
    }
}
