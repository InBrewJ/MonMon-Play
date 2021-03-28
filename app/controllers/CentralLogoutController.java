package controllers;

import com.typesafe.config.Config;
import org.pac4j.play.LogoutController;
import javax.inject.Inject;

public class CentralLogoutController extends LogoutController {

    private final Config config;

    @Inject
    public CentralLogoutController(Config config) {
        this.config = config;
        setDefaultUrl(config.getString("baseUrl") + "/?defaulturlafterlogoutafteridp");
        setLocalLogout(true);
        setCentralLogout(true);
        setLogoutUrlPattern(config.getString("baseUrl") + "/.*");
    }
}
