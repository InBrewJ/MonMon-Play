package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import models.Pot;
import models.PotRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.MistralService;
import viewModels.SimpleUserProfile;
import viewModels.SpendingPlan;

import javax.inject.Inject;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static helpers.MathHelpers.round2;
import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;

public class NomMonController extends Controller {

    private final MistralService mistralService;
    private final PotRepository potRepository;
    private final HttpExecutionContext ec;
    private final ObjectMapper mapper = new ObjectMapper();

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public NomMonController(
            MistralService mistralService,
            PotRepository potRepository,
            HttpExecutionContext ec
    ) {
        this.mistralService = mistralService;
        this.potRepository = potRepository;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return potRepository.list(sup.getUserId()).thenApplyAsync(potsStream -> {
            List<Pot> pots = potsStream.collect(java.util.stream.Collectors.toList());
            Pot activePot = pots.stream().filter(p -> p != null && p.type == Pot.PotType.MONTHLY).findFirst().orElse(null);

            Float weeklyFoodBudget = SpendingPlan.UK_AVG_WEEKLY_FOOD_SPEND;
            if (activePot != null && activePot.spendingPlanFood != null && activePot.spendingPlanFood > 0) {
                weeklyFoodBudget = round2((activePot.spendingPlanFood * 12.0f) / 52.0f);
            }

            boolean isConfigured = mistralService.isConfigured();
            return ok(views.html.nommon.render(
                    weeklyFoodBudget,
                    isConfigured,
                    request,
                    playSessionStore
            ));
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> getPlan(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        if (sup == null || "guest".equals(sup.getUserId())) {
            return java.util.concurrent.CompletableFuture.completedFuture(unauthorized(play.libs.Json.newObject().put("error", "Unauthorized")));
        }
        String userId = sup.getUserId();
        boolean force = request.queryString("force").map("true"::equalsIgnoreCase).orElse(false);
        String postcode = request.queryString("postcode").orElse(null);

        return potRepository.list(userId).thenComposeAsync(potsStream -> {
            List<Pot> pots = potsStream.collect(java.util.stream.Collectors.toList());
            Pot activePot = pots.stream().filter(p -> p != null && p.type == Pot.PotType.MONTHLY).findFirst().orElse(null);

            float budget = SpendingPlan.UK_AVG_WEEKLY_FOOD_SPEND;
            String budgetQuery = request.queryString("budget").orElse(null);
            if (budgetQuery != null && !budgetQuery.trim().isEmpty()) {
                try {
                    budget = Float.parseFloat(budgetQuery.trim());
                } catch (Exception ignored) {}
            } else if (activePot != null && activePot.spendingPlanFood != null && activePot.spendingPlanFood > 0) {
                budget = round2((activePot.spendingPlanFood * 12.0f) / 52.0f);
            }

            int people = 1;
            String peopleQuery = request.queryString("people").orElse(null);
            if (peopleQuery != null && !peopleQuery.trim().isEmpty()) {
                try {
                    people = Math.max(1, Math.min(12, Integer.parseInt(peopleQuery.trim())));
                } catch (Exception ignored) {}
            }

            final float finalBudget = budget;
            final int finalPeople = people;
            return mistralService.generateNomMonPlan(userId, finalBudget, finalPeople, postcode, force)
                    .thenApply(rawJson -> {
                        ObjectNode res = play.libs.Json.newObject();
                        res.put("configured", mistralService.isConfigured());
                        Long ts = mistralService.getNomMonTimestamp(userId);
                        if (ts != null) {
                            res.put("lastGenerated", ts);
                        }
                        try {
                            JsonNode parsed = mapper.readTree(rawJson);
                            if (parsed.has("error") && !parsed.has("shoppingList")) {
                                res.put("error", parsed.path("error").asText());
                                if (parsed.has("rawPlan")) {
                                    res.set("rawPlan", parsed.get("rawPlan"));
                                }
                            } else {
                                res.set("plan", parsed);
                            }
                        } catch (Exception e) {
                            JsonNode repaired = services.MistralService.attemptRepairTruncatedJson(rawJson, mapper);
                            if (repaired != null) {
                                res.set("plan", repaired);
                            } else {
                                res.put("rawPlan", rawJson);
                                res.put("error", "Meal plan generation was incomplete. Please click Regenerate to retry.");
                            }
                        }
                        return ok(res);
                    });
        }, ec.current());
    }
}
