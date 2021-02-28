package controllers;

import models.Incoming;
import models.IncomingRepository;
import org.pac4j.play.java.Secure;
import play.data.FormFactory;
import play.data.Form;
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
    public IncomingController(FormFactory formFactory, MessagesApi messagesApi, IncomingRepository incomingRepository, HttpExecutionContext ec) {
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.form = formFactory.form(Incoming.class);
        this.incomingRepository = incomingRepository;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getIncomings() {
        return incomingRepository
                .list()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getIncomingsComplete() {
        return incomingRepository
                .listComplete()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient")
    public Result listIncomings(Http.Request request) throws ExecutionException, InterruptedException {
        List<Incoming> incomings = repoListToList(incomingRepository.list());
        return ok(views.html.incomings.render(
                asScala(incomings),
                this.form,
                false,
                request,
                messagesApi.preferred(request))
        );
    }

    @Secure(clients = "OidcClient")
    public Result listIncomingsWithPrefill(int id, Http.Request request) throws ExecutionException, InterruptedException {
        List<Incoming> incomings = repoListToList(incomingRepository.list());
        Incoming found = incomingRepository.findById(id).toCompletableFuture().get();
        Form<Incoming> prefilledForm = this.form.fill(found);
        System.out.println("prefilled name: " + prefilledForm.field("name").value());
        //
        return ok(views.html.incomings.render(
                asScala(incomings),
                prefilledForm,
                true,
                request,
                messagesApi.preferred(request))
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Updating Incoming with id : " + id);
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        // Update all fields here
        return incomingRepository
                .update(id, incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addIncoming(final Http.Request request) {
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
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
