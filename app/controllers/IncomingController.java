package controllers;

import models.Incoming;
import models.IncomingRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.FormFactory;
import play.data.Form;
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
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 *
 * */

public class IncomingController extends Controller {
    private final FormFactory formFactory;
    private final IncomingRepository incomingRepository;
    private final HttpExecutionContext ec;
    private final Form<Incoming> form;
    private MessagesApi messagesApi;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public IncomingController(FormFactory formFactory, MessagesApi messagesApi, IncomingRepository incomingRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.form = formFactory.form(Incoming.class);
        this.incomingRepository = incomingRepository;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getIncomings(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return incomingRepository
                .list(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getIncomingsComplete(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return incomingRepository
                .listComplete(sup.getUserId())
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public Result listIncomings(Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        return ok(views.html.incomings.render(
                asScala(incomings),
                this.form,
                false,
                request,
                playSessionStore,
                messagesApi.preferred(request))
        );
    }

    @Secure(clients = "OidcClient")
    public Result listIncomingsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        List<Incoming> incomings = repoListToList(incomingRepository.list(sup.getUserId()));
        Incoming found = incomingRepository.findById(id).toCompletableFuture().get();
        if (!found.getUserId().equals(sup.getUserId())) {
            return forbidden(views.html.error403.render());
        }
        Form<Incoming> prefilledForm = this.form.fill(found);
        System.out.println("prefilled name: " + prefilledForm.field("name").value());
        //
        return ok(views.html.incomings.render(
                asScala(incomings),
                prefilledForm,
                true,
                request,
                playSessionStore,
                messagesApi.preferred(request))
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        System.out.println("Updating Incoming with id : " + id);
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        // Need to somehow check the userId vs the incoming to be archived
        // Ideally there would be a way to keep from passing userId into
        // incomingRepository?
        return incomingRepository
                .update(id, incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addIncoming(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        incoming.setUserId(sup.getUserId());
        return incomingRepository
                .add(incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Deleting Incoming with id : " + id);
        // perhaps just update an 'archived' field here
        return incomingRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }
}
