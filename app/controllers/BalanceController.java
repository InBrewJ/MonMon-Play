package controllers;

import models.*;
import services.GoogleCalendarService;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.Form;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.time.LocalTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static helpers.TimeHelpers.generateUnixTimestamp;
import static helpers.UserHelpers.getSimpleUserProfile;
import static java.lang.Integer.parseInt;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

/**
 * The controller keeps all database operations behind the repository, and uses
 * {@link play.libs.concurrent.HttpExecutionContext} to provide access to the
 * {@link play.mvc.Http.Context} methods like {@code request()} and {@code flash()}.
 */
public class BalanceController extends Controller {

    private final FormFactory formFactory;
    private final AccountRepository accountRepository;
    private final BalanceRepository balanceRepository;
    private final HttpExecutionContext ec;
    private final Form<Balance> form;
    private final GoogleCalendarService gcalService;
    private MessagesApi messagesApi;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public BalanceController(FormFactory formFactory,
                             MessagesApi messagesApi,
                             BalanceRepository balanceRepository,
                             AccountRepository accountRepository,
                             HttpExecutionContext ec,
                             GoogleCalendarService gcalService) {
        this.formFactory = formFactory;
        this.accountRepository = accountRepository;
        this.balanceRepository = balanceRepository;
        this.messagesApi = messagesApi;
        this.form = formFactory.form(Balance.class);
        this.ec = ec;
        this.gcalService = gcalService;
    }

    // weird, roundabout stuff for now...
    // because need to get the account_id from the form
    // or do something like:
    // https://stackoverflow.com/questions/26129994/playframework-2-and-manytoone-form-binding
    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> addBalance(final Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        Balance balance = formFactory.form(Balance.class).bindFromRequest(request).get();
        balance.setTimestamp(generateUnixTimestamp());
        int accountIdFromForm = parseInt(request.body().asFormUrlEncoded().get("account_id")[0]);
        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        Account desiredAccount = accounts
                .stream()
                .filter(account -> account.getId() == accountIdFromForm  )
                .collect(Collectors.toList()).get(0);
        if (!desiredAccount.getUserId().equals(sup.getUserId())) {
            CompletableFuture.runAsync(() -> {
                forbidden(views.html.error403.render());
            });
        }
        balance.setAccount(desiredAccount);
        balance.setUserId(sup.getUserId());
        return balanceRepository
                .add(balance)
                .thenApplyAsync(p -> redirect(routes.BalanceController.listBalances()), ec.current());
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> getBalances(final Http.Request request) {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        return balanceRepository
                .list(sup.getUserId())
                .thenApplyAsync(balanceStream -> ok(toJson(balanceStream.collect(Collectors.toList()))), ec.current());
    }

    public static List<Balance> filterLastTenDaysBalancesPerAccount(List<Balance> allBalances) {
        if (allBalances == null || allBalances.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        java.time.ZoneId zone = java.time.ZoneId.of("Europe/London");
        java.time.LocalDate tenDaysAgo = java.time.LocalDate.now(zone).minusDays(10);

        // Group by Account ID
        java.util.Map<Long, List<Balance>> byAccount = new java.util.HashMap<>();
        for (Balance b : allBalances) {
            if (b.getAccount() != null && b.getAccount().getId() != null) {
                byAccount.computeIfAbsent(b.getAccount().getId(), k -> new java.util.ArrayList<>()).add(b);
            }
        }

        List<Balance> result = new java.util.ArrayList<>();

        for (java.util.Map.Entry<Long, List<Balance>> entry : byAccount.entrySet()) {
            List<Balance> accBalances = entry.getValue();
            accBalances.sort(java.util.Comparator.comparing(Balance::getTimestamp).reversed());

            // Collect distinct dates for this account
            java.util.Set<java.time.LocalDate> distinctDates = new java.util.LinkedHashSet<>();
            for (Balance b : accBalances) {
                if (b.getTimestamp() != null) {
                    java.time.LocalDate d = java.time.Instant.ofEpochSecond(b.getTimestamp()).atZone(zone).toLocalDate();
                    distinctDates.add(d);
                }
            }

            // Find the allowed dates: dates within the last 10 days, or up to 10 most recent distinct dates
            java.util.Set<java.time.LocalDate> allowedDates = new java.util.HashSet<>();
            int count = 0;
            for (java.time.LocalDate d : distinctDates) {
                if (!d.isBefore(tenDaysAgo) || count < 10) {
                    allowedDates.add(d);
                    count++;
                }
                if (count >= 10 && d.isBefore(tenDaysAgo)) {
                    break;
                }
            }

            for (Balance b : accBalances) {
                if (b.getTimestamp() != null) {
                    java.time.LocalDate d = java.time.Instant.ofEpochSecond(b.getTimestamp()).atZone(zone).toLocalDate();
                    if (allowedDates.contains(d)) {
                        result.add(b);
                    }
                }
            }
        }

        result.sort(java.util.Comparator.comparing(Balance::getTimestamp).reversed());
        return result;
    }

    private String buildChartBalancesJson(List<Balance> allBalances) {
        if (allBalances == null || allBalances.isEmpty()) {
            return "[]";
        }
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/London");
        long oneYearAgoSec = java.time.Instant.now().minus(365, java.time.temporal.ChronoUnit.DAYS).getEpochSecond();
        long sixtyDaysAgoSec = java.time.Instant.now().minus(60, java.time.temporal.ChronoUnit.DAYS).getEpochSecond();

        List<Balance> sorted = new java.util.ArrayList<>(allBalances);
        sorted.sort(java.util.Comparator.comparing(Balance::getTimestamp));

        List<java.util.Map<String, Object>> points = new java.util.ArrayList<>();
        java.util.Map<String, Balance> dailySampled = new java.util.HashMap<>();

        for (Balance b : sorted) {
            if (b.getAccount() == null || b.getTimestamp() == null || b.getTimestamp() < oneYearAgoSec) {
                continue;
            }
            if (b.getTimestamp() >= sixtyDaysAgoSec) {
                java.util.Map<String, Object> map = new java.util.HashMap<>();
                map.put("id", b.getId());
                map.put("accountId", b.getAccount().getId());
                map.put("accountName", b.getAccount().getName() != null ? b.getAccount().getName() : "");
                map.put("value", b.getValue() != null ? b.getValue() : 0f);
                map.put("timestamp", b.getTimestamp());
                map.put("dateStr", b.getTimestampHumanReadable());
                points.add(map);
            } else {
                java.time.LocalDate d = java.time.Instant.ofEpochSecond(b.getTimestamp()).atZone(zone).toLocalDate();
                String key = b.getAccount().getId() + "_" + d.toString();
                dailySampled.put(key, b);
            }
        }

        List<Balance> sampledList = new java.util.ArrayList<>(dailySampled.values());
        sampledList.sort(java.util.Comparator.comparing(Balance::getTimestamp));
        for (Balance b : sampledList) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", b.getId());
            map.put("accountId", b.getAccount().getId());
            map.put("accountName", b.getAccount().getName() != null ? b.getAccount().getName() : "");
            map.put("value", b.getValue() != null ? b.getValue() : 0f);
            map.put("timestamp", b.getTimestamp());
            map.put("dateStr", b.getTimestampHumanReadable());
            points.add(map);
        }

        points.sort((p1, p2) -> Long.compare((Long) p1.get("timestamp"), (Long) p2.get("timestamp")));
        return play.libs.Json.stringify(play.libs.Json.toJson(points));
    }

    private String buildChartEventsJson(List<CalendarEvent> events) {
        if (events == null || events.isEmpty()) {
            return "[]";
        }
        List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        for (CalendarEvent e : events) {
            java.util.Map<String, Object> map = new java.util.HashMap<>();
            map.put("id", e.getId());
            map.put("title", e.getTitle() != null ? e.getTitle() : "");
            map.put("date", e.getDate() != null ? e.getDate() : "");
            list.add(map);
        }
        return play.libs.Json.stringify(play.libs.Json.toJson(list));
    }

    @Secure(clients = "OidcClient")
    public CompletionStage<Result> listBalances(Http.Request request) throws ExecutionException, InterruptedException {
        SimpleUserProfile sup = getSimpleUserProfile(playSessionStore, request);
        long tenDaysAgoSec = java.time.Instant.now().minus(10, java.time.temporal.ChronoUnit.DAYS).getEpochSecond();
        long oneYearAgoSec = java.time.Instant.now().minus(365, java.time.temporal.ChronoUnit.DAYS).getEpochSecond();

        // 1. Query database directly for only last 10 days balances (table)
        List<Balance> recentBalances = repoListToList(balanceRepository.listSince(sup.getUserId(), tenDaysAgoSec));

        // 2. Query database directly for only 1 year balances (chart)
        List<Balance> chartBalances = repoListToList(balanceRepository.listSince(sup.getUserId(), oneYearAgoSec));

        List<Account> accounts = repoListToList(accountRepository.list(sup.getUserId()));
        String chartBalancesJson = buildChartBalancesJson(chartBalances);

        java.time.LocalDate now = java.time.LocalDate.now();
        java.time.LocalDate oneYearAgo = now.minusYears(1);

        return gcalService.getEventsForYear(oneYearAgo, now)
                .thenApplyAsync(events -> {
                    String chartEventsJson = buildChartEventsJson(events);
                    return ok(
                            views.html.balances.render(
                                    asScala(recentBalances),
                                    this.form,
                                    asScala(accounts),
                                    chartBalancesJson,
                                    chartEventsJson,
                                    request,
                                    playSessionStore,
                                    messagesApi.preferred(request)
                            )
                    );
                }, ec.current());
    }

}
