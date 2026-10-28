package controllers;

import helpers.UserHelpers;
import models.BankTransaction;
import models.BankTransactionRepository;
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
    private final MessagesApi messagesApi;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public DigController(BankTransactionRepository bankTransactionRepository,
                         MonzoConnectionRepository monzoConnectionRepository,
                         MessagesApi messagesApi,
                         HttpExecutionContext ec) {
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);

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
                                                            DATA_LIMIT
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

                                    response.put("transactions", txData);
                                    response.put("histogram", histData);
                                    response.put("totalTransactions", txData.size());
                                    response.put("limitReached", geoList.size() >= DATA_LIMIT);
                                    response.put("limit", DATA_LIMIT);
                                    return ok(toJson(response));
                                }, ec.current()),
                        ec.current());
    }
}
