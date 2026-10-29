package controllers;

import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static play.test.Helpers.contentAsString;

public class LandingControllerTest {

    @Test
    public void testLandingPageRendersForUnauthenticatedUsers() {
        SessionStore sessionStore = mock(SessionStore.class);
        LandingController controller = new LandingController(sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/").build();
        Result result = controller.index(request);

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("What is MonMon?");
        assertThat(body).contains("What is NomMon?");
        assertThat(body).contains("Single Pane of Glass");
        assertThat(body).contains("Log In / Open SPOG");
        assertThat(body).contains("href=\"/spog\"");
    }

    @Test
    public void testAboutPageRenders() {
        SessionStore sessionStore = mock(SessionStore.class);
        LandingController controller = new LandingController(sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/about").build();
        Result result = controller.about(request);

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("NomMon Food Planner");
    }

    @Test
    public void testHealthEndpointReturnsOkJson() {
        SessionStore sessionStore = mock(SessionStore.class);
        LandingController controller = new LandingController(sessionStore);

        Http.Request request = Helpers.fakeRequest("GET", "/health").build();
        Result result = controller.health(request);

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        assertThat(body).contains("\"status\":\"ok\"");
        assertThat(body).contains("\"service\":\"MonMon\"");
    }
}
