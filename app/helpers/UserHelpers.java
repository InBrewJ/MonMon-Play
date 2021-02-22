package helpers;

import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.play.PlayWebContext;
import play.mvc.Http;
import viewModels.SimpleUserProfile;

import java.util.List;

public class UserHelpers {
    static public List<UserProfile> getAuthProfiles(SessionStore playSessionStore, Http.Request request) {
        final PlayWebContext context = new PlayWebContext(request);
        final ProfileManager profileManager = new ProfileManager(context, playSessionStore);
        List<UserProfile> profiles = profileManager.getProfiles();
        System.out.println("Profiles:");
        for (UserProfile up: profiles) {
            System.out.println(up.getUsername());
            System.out.println(up.getId());
        }
        return profiles;
    }

    static public SimpleUserProfile getSimpleUserProfile(SessionStore playSessionStore, Http.Request request) {
        List<UserProfile> allProfiles = getAuthProfiles(playSessionStore, request);
        SimpleUserProfile sup = new SimpleUserProfile();
        if (!allProfiles.isEmpty()) {
            UserProfile firstUp = allProfiles.get(0);
            sup.setUserId(firstUp.getId());
            sup.setUsername(firstUp.getUsername());
            sup.setUserEmail(firstUp.getAttribute("email").toString());
        } else {
            sup.setUserId("userId: error");
            sup.setUsername("username: error");
            sup.setUserEmail("email: error");
        }
        return sup;
    }
}
