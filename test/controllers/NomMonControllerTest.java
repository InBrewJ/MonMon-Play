package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import models.LockedMealPlan;
import models.MealPlanRepository;
import models.Pot;
import models.PotRepository;
import org.junit.Test;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.core.profile.CommonProfile;
import org.pac4j.core.profile.UserProfile;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Http;
import play.mvc.Result;
import play.test.Helpers;
import services.MistralService;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static play.test.Helpers.contentAsString;

public class NomMonControllerTest {

    private void injectSessionStore(NomMonController controller, SessionStore sessionStore) throws Exception {
        Field f = NomMonController.class.getDeclaredField("playSessionStore");
        f.setAccessible(true);
        f.set(controller, sessionStore);
    }

    private void mockUserProfile(SessionStore sessionStore, String userId) {
        CommonProfile profile = new CommonProfile();
        profile.setId(userId);
        profile.addAttribute("preferred_username", "tester");
        profile.addAttribute("user_access_status", "APPROVED");
        LinkedHashMap<String, UserProfile> profiles = new LinkedHashMap<>();
        profiles.put("OidcClient", profile);
        when(sessionStore.get(any(), eq("pac4jUserProfiles"))).thenReturn(Optional.of(profiles));
    }

    @Test
    public void testGetPlanReturnsLockedPlanFromDatabaseWhenPresent() throws Exception {
        MistralService mistral = mock(MistralService.class);
        PotRepository potRepo = mock(PotRepository.class);
        MealPlanRepository mealPlanRepo = mock(MealPlanRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-nom");
        when(mistral.isConfigured()).thenReturn(true);

        String samplePlanJson = "{\"planTitle\":\"Grand Autumn Feasts\",\"shoppingList\":[{\"item\":\"Pasta\",\"price\":1.5}],\"weeklyBudget\":45.0}";
        LockedMealPlan locked = new LockedMealPlan("u-nom", samplePlanJson, 45.0f, 1, "BS1 4AA", 1700000000L);
        when(mealPlanRepo.findByUserId(eq("u-nom"))).thenReturn(CompletableFuture.completedFuture(Optional.of(locked)));

        NomMonController controller = new NomMonController(mistral, potRepo, mealPlanRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(Helpers.fakeRequest("GET", "/api/nommon/plan")).build();
        Result result = controller.getPlan(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        String body = contentAsString(result);
        JsonNode json = play.libs.Json.parse(body);
        assertThat(json.get("locked").asBoolean()).isTrue();
        assertThat(json.get("plan").get("planTitle").asText()).isEqualTo("Grand Autumn Feasts");
        // Verify mistral was NOT called because locked DB plan was served
        verify(mistral, never()).generateNomMonPlan(any(), anyFloat(), anyInt(), any(), anyBoolean());
    }

    @Test
    public void testLockPlanSavesLockedMealPlanToDatabase() throws Exception {
        MistralService mistral = mock(MistralService.class);
        PotRepository potRepo = mock(PotRepository.class);
        MealPlanRepository mealPlanRepo = mock(MealPlanRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-nom");

        LockedMealPlan saved = new LockedMealPlan("u-nom", "{}", 50.0f, 1, "BS1", 1700000500L);
        when(mealPlanRepo.saveOrUpdate(any(LockedMealPlan.class))).thenReturn(CompletableFuture.completedFuture(saved));

        NomMonController controller = new NomMonController(mistral, potRepo, mealPlanRepo, ec);
        injectSessionStore(controller, sessionStore);

        JsonNode payload = play.libs.Json.newObject()
                .put("budget", 50.0)
                .put("people", 1)
                .put("postcode", "BS1")
                .set("plan", play.libs.Json.newObject().put("planTitle", "Locked Week"));

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(
                Helpers.fakeRequest("POST", "/api/nommon/lock").bodyJson(payload)
        ).build();

        Result result = controller.lockPlan(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        JsonNode json = play.libs.Json.parse(contentAsString(result));
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("locked").asBoolean()).isTrue();
        verify(mealPlanRepo, times(1)).saveOrUpdate(any(LockedMealPlan.class));
    }

    @Test
    public void testUnlockPlanDeletesLockedMealPlanFromDatabase() throws Exception {
        MistralService mistral = mock(MistralService.class);
        PotRepository potRepo = mock(PotRepository.class);
        MealPlanRepository mealPlanRepo = mock(MealPlanRepository.class);
        HttpExecutionContext ec = new HttpExecutionContext(ForkJoinPool.commonPool());
        SessionStore sessionStore = mock(SessionStore.class);

        mockUserProfile(sessionStore, "u-nom");
        when(mealPlanRepo.deleteByUserId(eq("u-nom"))).thenReturn(CompletableFuture.completedFuture(true));

        NomMonController controller = new NomMonController(mistral, potRepo, mealPlanRepo, ec);
        injectSessionStore(controller, sessionStore);

        Http.Request request = play.api.test.CSRFTokenHelper.addCSRFToken(
                Helpers.fakeRequest("POST", "/api/nommon/unlock")
        ).build();

        Result result = controller.unlockPlan(request).toCompletableFuture().get();

        assertThat(result.status()).isEqualTo(200);
        JsonNode json = play.libs.Json.parse(contentAsString(result));
        assertThat(json.get("success").asBoolean()).isTrue();
        assertThat(json.get("locked").asBoolean()).isFalse();
        verify(mealPlanRepo, times(1)).deleteByUserId(eq("u-nom"));
    }
}
