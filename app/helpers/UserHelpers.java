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
        return getAuthProfiles(playSessionStore, (Http.RequestHeader) request);
    }

    static public List<UserProfile> getAuthProfiles(SessionStore playSessionStore, Http.RequestHeader requestHeader) {
        final PlayWebContext context = new PlayWebContext(requestHeader);
        final ProfileManager profileManager = new ProfileManager(context, playSessionStore);
        return profileManager.getProfiles();
    }

    static public boolean isAuthenticated(SessionStore playSessionStore, Http.Request request) {
        return isAuthenticated(playSessionStore, (Http.RequestHeader) request);
    }

    static public boolean isAuthenticated(SessionStore playSessionStore, Http.RequestHeader requestHeader) {
        List<UserProfile> allProfiles = getAuthProfiles(playSessionStore, requestHeader);
        return allProfiles != null && !allProfiles.isEmpty();
    }

    static public SimpleUserProfile getSimpleUserProfile(SessionStore playSessionStore, Http.Request request) {
        return getSimpleUserProfile(playSessionStore, (Http.RequestHeader) request);
    }

    static public SimpleUserProfile getSimpleUserProfile(SessionStore playSessionStore, Http.RequestHeader requestHeader) {
        List<UserProfile> allProfiles = getAuthProfiles(playSessionStore, requestHeader);
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

    static public boolean isUserApproved(SessionStore playSessionStore, Http.Request request) {
        return isUserApproved(playSessionStore, (Http.RequestHeader) request);
    }

    static public boolean isUserApproved(SessionStore playSessionStore, Http.RequestHeader requestHeader) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, requestHeader);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return false;
        }
        models.JPAUserAccessRepository repo = models.JPAUserAccessRepository.getInstance();
        if (repo != null) {
            return repo.isUserApprovedSync(sup.getUserId());
        }
        return true;
    }
}

