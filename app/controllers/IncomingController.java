package controllers;

import models.Incoming;
import models.IncomingRepository;
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
import java.util.stream.Stream;

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

    private <T> List<T> repoListToList(CompletionStage<Stream<T>> in) throws ExecutionException, InterruptedException {
        return in.toCompletableFuture().get().collect(Collectors.toList());
    }

    public CompletionStage<Result> getIncomings() {
        return incomingRepository
                .list()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    public Result listIncomings(Http.Request request) throws ExecutionException, InterruptedException {
        List<Incoming> incomings = repoListToList(incomingRepository.list());
        return ok(views.html.incomings.render(asScala(incomings), this.form, request, messagesApi.preferred(request) ));
    }

    public CompletionStage<Result> addIncoming(final Http.Request request) {
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        System.out.println(incoming.getName());
        System.out.println(incoming.isPayDay());
        return incomingRepository
                .add(incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }
}
