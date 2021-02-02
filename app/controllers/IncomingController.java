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

    public CompletionStage<Result> getIncomings() {
        return incomingRepository
                .list()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    public CompletionStage<Result> getIncomingsComplete() {
        return incomingRepository
                .listComplete()
                .thenApplyAsync(incomingStream -> ok(toJson(incomingStream.collect(Collectors.toList()))), ec.current());
    }

    public Result listIncomings(Http.Request request) throws ExecutionException, InterruptedException {
        List<Incoming> incomings = repoListToList(incomingRepository.list());
        // MWM-15 playing around with form pre-filling
        // seems to work fine
        // We need a separate method in the controller to:
        // - find an Incoming by id
        // - prefill the form
        // - POST the data back (again with the id, maybe a hidden field?)
        //     - the id will also be in the URL, don't forget
        // - form data will go to a generic Incoming update method
        //     - this will find the model and update it, just like archive
        // Then boom, done.
        // Then, how to make it more generic for all the models...
        Incoming toFill = new Incoming();
        toFill.setName("Super Ovo");
        toFill.setNetValue(1000000f);
        toFill.setType("Salary");
        toFill.setIncomingMonthDay(1);
        toFill.setPayDay(false);
        Form<Incoming> populatedForm = this.form.fill(toFill);
        System.out.println("prefilled name: " + populatedForm.field("name").value());
        //
        return ok(views.html.incomings.render(
                asScala(incomings),
                populatedForm,
                request,
                messagesApi.preferred(request))
        );
    }

    public CompletionStage<Result> addIncoming(final Http.Request request) {
        Incoming incoming = formFactory.form(Incoming.class).bindFromRequest(request).get();
        return incomingRepository
                .add(incoming)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }

    public CompletionStage<Result> archiveIncoming(int id, final Http.Request request) throws ExecutionException, InterruptedException {
        System.out.println("Deleting Incoming with id : " + id);
        // perhaps just update an 'archived' field here
        return incomingRepository
                .archive(id)
                .thenApplyAsync(p -> redirect(routes.IncomingController.listIncomings()), ec.current());
    }
}
