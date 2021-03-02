package helpers;

import org.pac4j.core.context.session.SessionStore;
import play.mvc.Http;
import viewModels.SimpleUserProfile;

import static helpers.UserHelpers.getSimpleUserProfile;

public class UIHelpers {

    // MWM-35
    // Instead of having a static method here,
    // we could have some sort of factory that (somehow)
    // retrieves a SimpleUserProfile from helpers.UserHelpers :shrug:
    // https://www.playframework.com/documentation/2.8.x/JavaTemplates
    // https://alvinalexander.com/scala/how-use-twirl-templates-standalone-play-framework/

    static public String userDetails(SessionStore playSessionStore, Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return sup.getUsername() + " (" + sup.getUserEmail() + ")";
    }
}
