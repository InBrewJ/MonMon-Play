package controllers;

import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.UserProfile;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;

import javax.inject.Inject;
import java.util.List;

import static helpers.UserHelpers.getAuthProfiles;

public class LandingController extends Controller {

    private final SessionStore playSessionStore;

    @Inject
    public LandingController(SessionStore playSessionStore) {
        this.playSessionStore = playSessionStore;
    }

    public Result index(final Http.Request request) {
        List<UserProfile> profiles = getAuthProfiles(playSessionStore, request);
        boolean isAuthenticated = profiles != null && !profiles.isEmpty();

        // Authenticated users landing on / are routed straight to their SPOG dashboard
        if (isAuthenticated) {
            return redirect(routes.SpogController.index());
        }

        return ok(views.html.landing.render(request));
    }

    public Result about(final Http.Request request) {
        return ok(views.html.landing.render(request));
    }

    public Result health(final Http.Request request) {
        return ok(play.libs.Json.newObject()
                .put("status", "ok")
                .put("service", "MonMon")
                .put("timestamp", System.currentTimeMillis()));
    }
}
