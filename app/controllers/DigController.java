package controllers;

import helpers.UserHelpers;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.IncomingRepository;
import models.MonzoConnectionRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import viewModels.CategorySpend;
import viewModels.DigViewModel;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.CompletionStage;

import static play.libs.Json.toJson;

public class DigController extends Controller {

    private static final int DATA_LIMIT = 2000;

    private final BankTransactionRepository bankTransactionRepository;
    private final MonzoConnectionRepository monzoConnectionRepository;
    private final IncomingRepository incomingRepository;
    private final MessagesApi messagesApi;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public DigController(BankTransactionRepository bankTransactionRepository,
                         MonzoConnectionRepository monzoConnectionRepository,
                         IncomingRepository incomingRepository,
                         MessagesApi messagesApi,
                         HttpExecutionContext ec) {
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.incomingRepository = incomingRepository;
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    public DigController(BankTransactionRepository bankTransactionRepository,
                         MonzoConnectionRepository monzoConnectionRepository,
                         MessagesApi messagesApi,
                         HttpExecutionContext ec) {
        this(bankTransactionRepository, monzoConnectionRepository, null, messagesApi, ec);
    }

    private int resolveNominalPayday(String userId) {
        if (incomingRepository == null || userId == null) {
            return 1;
        }
        try {
            int day = incomingRepository.getNextPayDay(userId);
            return (day >= 1 && day <= 31) ? day : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        int nominalPayday = resolveNominalPayday(sup.getUserId());

        return bankTransactionRepository.findGeotaggedTransactions(sup.getUserId(), DATA_LIMIT)
                .thenComposeAsync(geoList ->
                        bankTransactionRepository.getCategorySpendingHistogram(sup.getUserId())
                                .thenComposeAsync(histogram ->
                                        monzoConnectionRepository.findByUserId(sup.getUserId())
                                                .thenApplyAsync(monzoConnOpt -> {
                                                    boolean isMonzoConnected = monzoConnOpt.isPresent();
                                                    boolean limitReached = geoList.size() >= DATA_LIMIT;

                                                    DigViewModel vm = new DigViewModel(
                                                            geoList,
                                                            histogram,
                                                            isMonzoConnected,
                                                            limitReached,
                                                            DATA_LIMIT,
                                                            nominalPayday
                                                    );

                                                    return ok(views.html.dig.render(
                                                            vm,
                                                            request,
                                                            messagesApi.preferred(request)
                                                    ));
                                                }, ec.current()),
                                ec.current()),
                        ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> apiData(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        int nominalPayday = resolveNominalPayday(sup.getUserId());

        return bankTransactionRepository.findGeotaggedTransactions(sup.getUserId(), DATA_LIMIT)
                .thenComposeAsync(geoList ->
                        bankTransactionRepository.getCategorySpendingHistogram(sup.getUserId())
                                .thenApplyAsync(histogram -> {
                                    Map<String, Object> response = new HashMap<>();
                                    List<Map<String, Object>> txData = new ArrayList<>();
                                    for (BankTransaction t : geoList) {
                                        Map<String, Object> item = new HashMap<>();
                                        item.put("id", t.getId());
                                        item.put("lat", t.getLatitude());
                                        item.put("lng", t.getLongitude());
                                        item.put("amount", t.getAmount());
                                        item.put("merchant", t.getMerchantName() != null ? t.getMerchantName() : t.getDescription());
                                        item.put("category", t.getCategory());
                                        item.put("emoji", t.getMerchantEmoji());
                                        item.put("address", t.getMerchantAddress());
                                        item.put("date", t.getDate());
                                        item.put("timestamp", t.getTimestamp() != null ? t.getTimestamp() : 0L);
                                        txData.add(item);
                                    }

                                    List<Map<String, Object>> histData = new ArrayList<>();
                                    for (CategorySpend cs : histogram) {
                                        Map<String, Object> item = new HashMap<>();
                                        item.put("category", cs.getFormattedCategory());
                                        item.put("rawCategory", cs.getCategory());
                                        item.put("count", cs.getCount());
                                        item.put("total", cs.getTotalSpent());
                                        item.put("average", cs.getAverageSpent());
                                        item.put("percentage", cs.getPercentage());
                                        histData.add(item);
                                    }

                                    DigViewModel vm = new DigViewModel(
                                            geoList,
                                            histogram,
                                            false,
                                            geoList.size() >= DATA_LIMIT,
                                            DATA_LIMIT,
                                            nominalPayday
                                    );

                                    Map<String, Object> cyclesMap = new HashMap<>();
                                    cyclesMap.put("nominalPayday", vm.getNominalPayday());

                                    Map<String, Object> thisMonth = new HashMap<>();
                                    thisMonth.put("startDate", vm.getThisMonthStartDate());
                                    thisMonth.put("endDate", vm.getThisMonthEndDate());
                                    thisMonth.put("label", vm.getThisMonthDateRangeLabel());
                                    cyclesMap.put("thisMonth", thisMonth);

                                    Map<String, Object> last2Cycles = new HashMap<>();
                                    last2Cycles.put("startDate", vm.getLast2CyclesStartDate());
                                    last2Cycles.put("endDate", vm.getLast2CyclesEndDate());
                                    last2Cycles.put("label", vm.getLast2CyclesDateRangeLabel());
                                    cyclesMap.put("last2Cycles", last2Cycles);

                                    Map<String, Object> last6Cycles = new HashMap<>();
                                    last6Cycles.put("startDate", vm.getLast6CyclesStartDate());
                                    last6Cycles.put("endDate", vm.getLast6CyclesEndDate());
                                    last6Cycles.put("label", vm.getLast6CyclesDateRangeLabel());
                                    cyclesMap.put("last6Cycles", last6Cycles);

                                    response.put("transactions", txData);
                                    response.put("histogram", histData);
                                    response.put("cycles", cyclesMap);
                                    response.put("totalTransactions", txData.size());
                                    response.put("limitReached", geoList.size() >= DATA_LIMIT);
                                    response.put("limit", DATA_LIMIT);
                                    return ok(toJson(response));
                                }, ec.current()),
                        ec.current());
    }
}
