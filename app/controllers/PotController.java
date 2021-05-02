package controllers;

import models.*;
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
import java.util.concurrent.ExecutionException;

import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;
import static play.libs.Scala.asScala;

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
 * A monthly pot needs a list of accounts
 * one pot has many accounts
 *
 * Pot has type (MONTHLY / YEARLY(?) / SAVING_TARGET)
 * MONTHLY will be displayed as pot / days left before payday etc
 *
 * So a Pot is a model. Needs a name and needs a list of accounts (ids)
 */
public class PotController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private MessagesApi messagesApi;
    private final Form<Plan> form;
    private final PotRepository potRepository;
    private final AccountRepository accountRepository;
    private List<Account> accounts;
    private List<Pot> pots;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public PotController(FormFactory formFactory, MessagesApi messagesApi, PotRepository potRepository, AccountRepository accountRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.potRepository = potRepository;
        this.accountRepository = accountRepository;
        this.form = formFactory.form(Plan.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result monthlyPot(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        this.accounts = repoListToList(accountRepository.list(sup.getUserId()));
        this.pots = repoListToList(potRepository.listComplete(sup.getUserId()));
        return ok(
                views.html.pots.render(
                        asScala(accounts),
                        asScala(pots),
                        false,
                        request,
                        playSessionStore,
                        messagesApi.preferred(request)
                )
        );
    }
}
