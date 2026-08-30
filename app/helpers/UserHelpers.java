package helpers;

import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.ProfileManager;
import org.pac4j.core.profile.UserProfile;
import org.pac4j.play.PlayWebContext;
import play.mvc.Http;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.List;

public class UserHelpers {

    @Inject
    private SessionStore playSessionStore;

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
        if (allProfiles != null && !allProfiles.isEmpty()) {
            UserProfile firstUp = allProfiles.get(0);
            String id = firstUp.getId();
            sup.setUserId(id != null ? id : "default-user");

            String username = firstUp.getUsername();
            if (username == null || username.trim().isEmpty()) {
                Object pref = firstUp.getAttribute("preferred_username");
                username = pref != null ? pref.toString() : "User";
            }
            sup.setUsername(username);

            Object emailObj = firstUp.getAttribute("email");
            String email = emailObj != null ? emailObj.toString() : "";
            sup.setUserEmail(email);
        } else {
            sup.setUserId("guest");
            sup.setUsername("Guest");
            sup.setUserEmail("");
        }
        return sup;
    }
}
