package controllers;

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

import static helpers.UserHelpers.getSimpleUserProfile;

/**
 * PotController
 * It's a fun name
 *
 * This is the place where 'pots' are created
 * It starts with the 'monthlyPot', which is nothing
 * more than a list of accounts that you see as your
 * spending sources month to month
 *
 * In the future, other 'pots' may or may not exist
 * that can be used to save for different things
 *
 * For example, the 'holiday pot' could take the balances
 * of a few savings account, add them together and compare
 * them with a 'pot target'
 *
 */
public class PotController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final PlanRepository planRepository;
    private MessagesApi messagesApi;
    private final Form<Plan> form;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public PotController(FormFactory formFactory, MessagesApi messagesApi, PlanRepository planRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.planRepository = planRepository;
        this.form = formFactory.form(Plan.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result monthlyPot(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return ok("monthly pots");
    }
}
