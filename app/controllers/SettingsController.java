package controllers;

import helpers.BankHolidayHelper;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.Optional;

import static helpers.UserHelpers.getSimpleUserProfile;

public class SettingsController extends Controller {
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;
    private final MessagesApi messagesApi;
    private final SessionStore sessionStore;

    @Inject
    public SettingsController(FormFactory formFactory,
                              HttpExecutionContext ec,
                              MessagesApi messagesApi,
                              SessionStore sessionStore) {
        this.formFactory = formFactory;
        this.ec = ec;
        this.messagesApi = messagesApi;
        this.sessionStore = sessionStore;
    }

    @Secure(clients = "OidcClient")
    public Result index(Http.Request request) {
        String region = request.session().get("region").orElse("UK");
        return ok(views.html.settings.render(region, request, this.sessionStore, messagesApi.preferred(request)));
    }

    @Secure(clients = "OidcClient")
    public Result updateSettings(Http.Request request) {
        DynamicForm requestData = formFactory.form().bindFromRequest(request);
        String region = Optional.ofNullable(requestData.get("region")).orElse("UK");
        return redirect(routes.SettingsController.index())
                .addingToSession(request, "region", region)
                .flashing("success", "Settings updated successfully! Region set to: " + region);
    }
}
