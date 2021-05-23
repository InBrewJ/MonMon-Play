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
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
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
    private final Form<Pot> form;
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
        this.form = formFactory.form(Pot.class);
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result monthlyPot(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        this.accounts = repoListToList(accountRepository.list(sup.getUserId()));
        this.pots = repoListToList(potRepository.list(sup.getUserId()));
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

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addPot(final Http.Request request) throws ExecutionException, InterruptedException {
        // Couldn't quite figure out how to add accounts to a pot
        // It seems to be the case that you can't set the pot_id
        // in the account before the pot is persisted, bc how else
        // do you know the pot_id until the pot is persisted?
        //
        // Generally, maybe create a pot first, _then_ add accounts to it?
        //
        // Turns out one solution is to use JPA's EntityManager.merge (hm)
        //
        // See this:
        // https://vladmihalcea.com/the-best-way-to-map-a-onetomany-association-with-jpa-and-hibernate/
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Pot pot = formFactory.form(Pot.class).bindFromRequest(request).get();
        Pot scratchPot = new Pot();
        scratchPot.setType(Pot.PotType.MONTHLY);
        scratchPot.setCreatedAt(generateUnixTimestamp());
        scratchPot.setLastUpdated(generateUnixTimestamp());
        scratchPot.setUserId(sup.getUserId());
        System.out.println("Accounts after addPot ::");
        System.out.println(request.body().asFormUrlEncoded().get("accounts")[0]);
        String[] accountFormMap = request.body().asFormUrlEncoded().get("accounts");
        // get all accounts selected
        for (String s : accountFormMap) {
            System.out.println("accounts selected :: " + s);
            Account selectedAccount = accountRepository.findById(parseInt(s)).toCompletableFuture().get();
            scratchPot.addAccount(selectedAccount);
        }
//        Account potAccount = getAccountsFromFormRequest(request);
//        System.out.println("Adding this account only to pot :: " + potAccount.getName());
//        scratchPot.addAccount(potAccount);
        return potRepository
                .add(scratchPot)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archivePot(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Archiving pot with id : " + id);
        return potRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.PotController.monthlyPot()), ec.current());
    }
}
