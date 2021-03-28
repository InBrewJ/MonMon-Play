package modules;

import com.google.inject.AbstractModule;
import com.google.inject.Inject;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import org.pac4j.core.authorization.authorizer.RequireAnyRoleAuthorizer;
import org.pac4j.core.client.Clients;
import org.pac4j.core.config.Config;
import org.pac4j.core.context.HttpConstants;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.matching.matcher.PathMatcher;
import org.pac4j.oidc.client.OidcClient;
import org.pac4j.oidc.config.OidcConfiguration;
import org.pac4j.play.CallbackController;
import org.pac4j.play.LogoutController;
import org.pac4j.play.deadbolt2.Pac4jHandlerCache;
import org.pac4j.play.deadbolt2.Pac4jRoleHandler;
import org.pac4j.play.http.PlayHttpActionAdapter;
import org.pac4j.play.store.PlayCacheSessionStore;
import com.nimbusds.jose.JWSAlgorithm;
import play.Environment;

import java.io.File;
import java.util.Optional;

import static play.mvc.Results.forbidden;
import static play.mvc.Results.unauthorized;

public class SecurityModule extends AbstractModule {

    public final static String JWT_SALT = "12345678901234567890123456789012";

    private final com.typesafe.config.Config configuration;

    private static class MyPac4jRoleHandler implements Pac4jRoleHandler { }

    private final String baseUrl;

    public SecurityModule(final Environment environment, final com.typesafe.config.Config configuration) {
        this.configuration = configuration;
        this.baseUrl = configuration.getString("baseUrl");
    }

    @Override
    protected void configure() {

        bind(Pac4jRoleHandler.class).to(MyPac4jRoleHandler.class);

        //final PlayCacheSessionStore playCacheSessionStore = new PlayCacheSessionStore(getProvider(SyncCacheApi.class));
        //bind(SessionStore.class).toInstance(playCacheSessionStore);
        bind(SessionStore.class).to(PlayCacheSessionStore.class);

        // callback
        final CallbackController callbackController = new CallbackController();
        callbackController.setDefaultUrl("/");
        callbackController.setRenewSession(true);
        bind(CallbackController.class).toInstance(callbackController);

        // logout
        final LogoutController logoutController = new LogoutController();
        logoutController.setDefaultUrl("/?defaulturlafterlogout");
        logoutController.setDestroySession(true);
        bind(LogoutController.class).toInstance(logoutController);
    }

//    oidc.discoveryUri = "http://localhost:8080/auth/realms/monmon/.well-known/openid-configuration"
//    oidc.clientId = "monmon-web"
//    oidc.clientSecret = "01c283f5-69ff-42e4-a080-971629656fbb"

    @Provides @Singleton
    protected OidcClient provideOidcClient() {
        final OidcConfiguration oidcConfiguration = new OidcConfiguration();
        oidcConfiguration.setClientId(configuration.getString("oidc.clientId"));
        oidcConfiguration.setSecret(configuration.getString("oidc.clientSecret"));
        oidcConfiguration.setDiscoveryURI(configuration.getString("oidc.discoveryUri"));
        oidcConfiguration.addCustomParam("prompt", "consent");
        oidcConfiguration.setPreferredJwsAlgorithm(JWSAlgorithm.RS256);
        final OidcClient oidcClient = new OidcClient(oidcConfiguration);
        oidcClient.addAuthorizationGenerator((ctx, session, profile) -> { profile.addRole("ROLE_ADMIN"); return Optional.of(profile); });
        return oidcClient;
    }

    @Provides @Singleton
    protected Config provideConfig(OidcClient oidcClient) {
        System.out.println("provideConfig called!");

        final Clients clients = new Clients(baseUrl + "/callback", oidcClient);

        PlayHttpActionAdapter.INSTANCE.getResults().put(HttpConstants.UNAUTHORIZED, unauthorized(views.html.error401.render().toString()).as((HttpConstants.HTML_CONTENT_TYPE)));
        PlayHttpActionAdapter.INSTANCE.getResults().put(HttpConstants.FORBIDDEN, forbidden(views.html.error403.render().toString()).as((HttpConstants.HTML_CONTENT_TYPE)));

        final Config config = new Config(clients);
        config.addAuthorizer("admin", new RequireAnyRoleAuthorizer("ROLE_ADMIN"));
        return config;
    }
}
