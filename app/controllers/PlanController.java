package controllers;

import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;

public class PlanController extends Controller {
    private final HttpExecutionContext ec;

    @Inject
    public PlanController(HttpExecutionContext ec) {
        this.ec = ec;
    }

    public Result sharedOutgoings(final Http.Request request) {
        return ok(views.html.sharing.render(request));
    }
}
