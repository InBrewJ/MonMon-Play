package controllers;

import com.fasterxml.jackson.databind.node.ObjectNode;
import helpers.BankHolidayHelper;
import helpers.ModelHelpers;
import models.*;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.MistralService;
import viewModels.NetWorthViewModel;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

import static helpers.ModelHelpers.repoListToList;
import static helpers.UserHelpers.getSimpleUserProfile;

public class NetWorthController extends Controller {

    private final AccountRepository accountRepository;
    private final NetWorthAssetRepository assetRepository;
    private final BalanceRepository balanceRepository;
    private final IncomingRepository incomingRepository;
    private final OutgoingRepository outgoingRepository;
    private final PlanRepository planRepository;
    private final PotRepository potRepository;
    private final MistralService mistralService;
    private final FormFactory formFactory;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public NetWorthController(
            AccountRepository accountRepository,
            NetWorthAssetRepository assetRepository,
            BalanceRepository balanceRepository,
            IncomingRepository incomingRepository,
            OutgoingRepository outgoingRepository,
            PlanRepository planRepository,
            PotRepository potRepository,
            MistralService mistralService,
            FormFactory formFactory,
            HttpExecutionContext ec
    ) {
        this.accountRepository = accountRepository;
        this.assetRepository = assetRepository;
        this.balanceRepository = balanceRepository;
        this.incomingRepository = incomingRepository;
        this.outgoingRepository = outgoingRepository;
        this.planRepository = planRepository;
        this.potRepository = potRepository;
        this.mistralService = mistralService;
        this.formFactory = formFactory;
        this.ec = ec;
    }

    private NetWorthViewModel buildViewModel(SimpleUserProfile sup, Http.Request request) throws ExecutionException, InterruptedException {
        String region = request.session().get("region").orElse("UK");
        LocalDate today = BankHolidayHelper.nowInRegion(region);

        List<Account> accounts = repoListToList(this.accountRepository.list(sup.getUserId()));
        List<NetWorthAsset> assets = repoListToList(this.assetRepository.list(sup.getUserId()));
        List<Incoming> incomings = repoListToList(this.incomingRepository.list(sup.getUserId()));
        List<Outgoing> outgoings = repoListToList(this.outgoingRepository.list(sup.getUserId()));
        List<Plan> plans = repoListToList(this.planRepository.list(sup.getUserId()));
        List<Pot> pots = repoListToList(this.potRepository.list(sup.getUserId()));

        Float monthlySurplus = ModelHelpers.calculateMonthlyAllowance(incomings, outgoings, plans, today);

        Double foodCost = null;
        Pot activePot = pots.stream().filter(p -> p.getType() == Pot.PotType.MONTHLY && !p.isArchived()).findFirst().orElse(null);
        if (activePot != null && activePot.getSpendingPlanFood() != null && activePot.getSpendingPlanFood() > 0) {
            foodCost = (double) activePot.getSpendingPlanFood();
        }

        return new NetWorthViewModel(accounts, assets, monthlySurplus != null ? (double) monthlySurplus : 0.0, foodCost);
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result index(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        NetWorthViewModel vm = buildViewModel(sup, request);

        String region = request.session().get("region").orElse("UK");
        LocalDate today = BankHolidayHelper.nowInRegion(region);
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/London");

        List<Account> accounts = vm.getAccounts();
        List<NetWorthAsset> allAssets = repoListToList(this.assetRepository.listAll(sup.getUserId()));
        if (allAssets.isEmpty() && vm.getCustomAssets() != null && !vm.getCustomAssets().isEmpty()) {
            allAssets = vm.getCustomAssets();
        }
        List<Balance> balances = repoListToList(this.balanceRepository.list(sup.getUserId()));

        List<helpers.NetWorthHistoryCalculator.HistoryPoint> points =
                helpers.NetWorthHistoryCalculator.calculate(accounts, balances, allAssets, today, zone);
        String chartHistoryJson = helpers.NetWorthHistoryCalculator.buildChartHistoryJson(points);

        return ok(views.html.netWorth.render(vm, chartHistoryJson, request, playSessionStore));
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addAsset(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);

        String name = form.get("name");
        String categoryStr = form.get("category");
        String valueStr = form.get("value");
        String notes = form.get("notes");

        if (name == null || name.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    redirect(routes.NetWorthController.index()).flashing("error", "Asset name is required.")
            );
        }

        NetWorthAsset.AssetCategory category = NetWorthAsset.AssetCategory.OTHER;
        try {
            if (categoryStr != null) {
                category = NetWorthAsset.AssetCategory.valueOf(categoryStr);
            }
        } catch (Exception ignored) {}

        double value = 0.0;
        try {
            if (valueStr != null) {
                value = Double.parseDouble(valueStr.replaceAll("[£, ]", ""));
            }
        } catch (Exception ignored) {}

        String acquiredDateStr = form.get("acquiredDate");
        Long acquiredAt = null;
        if (acquiredDateStr != null && !acquiredDateStr.trim().isEmpty()) {
            try {
                LocalDate d = LocalDate.parse(acquiredDateStr.trim());
                acquiredAt = d.atStartOfDay(ZoneId.of("Europe/London")).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        NetWorthAsset asset = new NetWorthAsset(sup.getUserId(), name.trim(), category, value, notes, acquiredAt);
        return assetRepository.add(asset).thenApply(saved ->
                redirect(routes.NetWorthController.index()).flashing("success", "Added asset: " + saved.getName())
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> updateAsset(Long id, final Http.Request request) {
        DynamicForm form = formFactory.form().bindFromRequest(request);

        String name = form.get("name");
        String categoryStr = form.get("category");
        String valueStr = form.get("value");
        String notes = form.get("notes");

        NetWorthAsset.AssetCategory category = null;
        try {
            if (categoryStr != null) {
                category = NetWorthAsset.AssetCategory.valueOf(categoryStr);
            }
        } catch (Exception ignored) {}

        Double value = null;
        try {
            if (valueStr != null) {
                value = Double.parseDouble(valueStr.replaceAll("[£, ]", ""));
            }
        } catch (Exception ignored) {}

        String acquiredDateStr = form.get("acquiredDate");
        Long acquiredAt = null;
        if (acquiredDateStr != null && !acquiredDateStr.trim().isEmpty()) {
            try {
                LocalDate d = LocalDate.parse(acquiredDateStr.trim());
                acquiredAt = d.atStartOfDay(ZoneId.of("Europe/London")).toInstant().toEpochMilli();
            } catch (Exception ignored) {}
        }

        return assetRepository.update(id, name, category, value, notes, acquiredAt).thenApply(updated ->
                redirect(routes.NetWorthController.index()).flashing("success", "Updated asset.")
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> archiveAsset(Long id, final Http.Request request) {
        return assetRepository.archive(id).thenApply(done ->
                redirect(routes.NetWorthController.index()).flashing("info", "Asset removed from active net worth.")
        );
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> edSaysNetWorth(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        boolean force = request.queryString("force").map(v -> "true".equalsIgnoreCase(v) || "1".equals(v)).orElse(false);

        return CompletableFuture.supplyAsync(() -> {
            try {
                NetWorthViewModel vm = buildViewModel(sup, request);
                return MistralService.buildNetWorthContextPrompt(vm);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, ec.current()).thenCompose(prompt ->
                mistralService.askEdNetWorth(sup.getUserId(), prompt, force)
                        .thenApply(advice -> {
                            ObjectNode res = play.libs.Json.newObject();
                            res.put("edSays", advice);
                            res.put("configured", mistralService.isConfigured());
                            Long lastSpoke = mistralService.getLastNetWorthSpokeTimestamp(sup.getUserId());
                            if (lastSpoke != null) {
                                res.put("lastSpoke", lastSpoke);
                            }
                            return ok(res);
                        })
        ).exceptionally(ex -> {
            ObjectNode err = play.libs.Json.newObject();
            err.put("edSays", "Ed took a long look at your portfolio but found no words: " + ex.getMessage());
            err.put("configured", mistralService.isConfigured());
            return ok(err);
        });
    }
}
