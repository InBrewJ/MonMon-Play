package controllers;

import helpers.UserHelpers;
import models.Account;
import models.AccountRepository;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.MonzoConnection;
import models.MonzoConnectionRepository;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.concurrent.HttpExecutionContext;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import services.BankSyncService;
import services.MockBankSyncProvider;
import services.MonzoBankSyncProvider;
import services.StatementParserService;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

public class BankSyncController extends Controller {

    private final AccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final MonzoConnectionRepository monzoConnectionRepository;
    private final MockBankSyncProvider mockBankSyncProvider;
    private final MonzoBankSyncProvider monzoBankSyncProvider;
    private final StatementParserService statementParserService;
    private final FormFactory formFactory;
    private final MessagesApi messagesApi;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public BankSyncController(AccountRepository accountRepository,
                              BankTransactionRepository bankTransactionRepository,
                              MonzoConnectionRepository monzoConnectionRepository,
                              MockBankSyncProvider mockBankSyncProvider,
                              MonzoBankSyncProvider monzoBankSyncProvider,
                              StatementParserService statementParserService,
                              FormFactory formFactory,
                              MessagesApi messagesApi,
                              HttpExecutionContext ec) {
        this.accountRepository = accountRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.mockBankSyncProvider = mockBankSyncProvider;
        this.monzoBankSyncProvider = monzoBankSyncProvider;
        this.statementParserService = statementParserService;
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    private BankSyncService resolveProvider(String providerName) {
        if ("MOCK".equalsIgnoreCase(providerName)) {
            return mockBankSyncProvider;
        }
        return monzoBankSyncProvider;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);

        return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
            List<Account> accounts = accStream.collect(Collectors.toList());
            return bankTransactionRepository.listRecent(sup.getUserId(), 100).thenComposeAsync(txStream -> {
                List<BankTransaction> txList = txStream.collect(Collectors.toList());
                return monzoConnectionRepository.findByUserId(sup.getUserId()).thenApplyAsync(monzoConnOpt -> {
                    boolean isMonzoConnected = monzoConnOpt.isPresent();
                    String providerName = isMonzoConnected ? "MONZO" : "MOCK";

                    return ok(views.html.bankSync.render(
                            asScala(accounts),
                            asScala(txList),
                            monzoConnOpt.orElse(null),
                            providerName,
                            isMonzoConnected,
                            accounts.size(),
                            txList.size(),
                            sup,
                            request,
                            messagesApi.preferred(request)
                    ));
                }, ec.current());
            }, ec.current());
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> syncAll(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);
        String providerReq = form.get("provider");
        BankSyncService provider = resolveProvider(providerReq);

        return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
            List<Account> accounts = accStream.collect(Collectors.toList());
            return provider.syncAllUserAccounts(sup.getUserId(), accounts, 30).thenApplyAsync(res -> {
                if (res.isSuccess()) {
                    return redirect(routes.BankSyncController.index())
                            .flashing("info", res.getMessage());
                } else {
                    return redirect(routes.BankSyncController.index())
                            .flashing("error", res.getMessage());
                }
            }, ec.current());
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> syncAccount(Long id, final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);
        String providerReq = form.get("provider");
        BankSyncService provider = resolveProvider(providerReq);

        return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
            Account target = accStream.filter(a -> a.getId().equals(id)).findFirst().orElse(null);
            if (target == null) {
                return CompletableFuture.completedFuture(
                        redirect(routes.BankSyncController.index()).flashing("error", "Account not found"));
            }
            return provider.syncAccount(target, 30).thenApplyAsync(res -> {
                if (res.isSuccess()) {
                    return redirect(routes.BankSyncController.index()).flashing("info", res.getMessage());
                } else {
                    return redirect(routes.BankSyncController.index()).flashing("error", res.getMessage());
                }
            }, ec.current());
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> saveMonzoToken(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);

        String token = form.get("accessToken");
        if (token == null || token.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Personal Access Token cannot be empty."));
        }
        final String cleanToken = token.trim();

        // Validate token by fetching Monzo accounts (supports Debit and Monzo Flex)
        return monzoBankSyncProvider.fetchAccounts(cleanToken).thenComposeAsync(monzoAccounts -> {
            MonzoConnection conn = new MonzoConnection(
                    sup.getUserId(),
                    cleanToken,
                    null,
                    "monzo-user",
                    System.currentTimeMillis() + (86400L * 365L * 1000L) // Personal access token
            );
            return monzoConnectionRepository.save(conn).thenComposeAsync(saved -> {
                // Immediately trigger sync
                return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
                    List<Account> accounts = accStream.collect(Collectors.toList());
                    return monzoBankSyncProvider.syncAllUserAccounts(sup.getUserId(), accounts, 30).thenApplyAsync(res -> {
                        return redirect(routes.BankSyncController.index())
                                .flashing("info", "Monzo connected successfully! Discovered " + monzoAccounts.size() + " accounts (including Debit & Monzo Flex). " + res.getMessage());
                    }, ec.current());
                }, ec.current());
            }, ec.current());
        }, ec.current()).exceptionally(err -> {
            return redirect(routes.BankSyncController.index())
                    .flashing("error", "Failed to connect to Monzo: " + err.getMessage() + ". Check that your token was copied correctly from developers.monzo.com");
        });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public Result connectMonzoOAuth(final Http.Request request) {
        String clientId = monzoBankSyncProvider.getClientId();
        if (clientId == null || clientId.trim().isEmpty()) {
            return redirect(routes.BankSyncController.index())
                    .flashing("error", "Monzo OAuth client ID is not configured. Use the Personal Access Token field below instead!");
        }
        String redirectUri = URLEncoder.encode(monzoBankSyncProvider.getRedirectUri(), StandardCharsets.UTF_8);
        String state = URLEncoder.encode(request.session().get("userId").orElse("user"), StandardCharsets.UTF_8);
        String url = "https://auth.monzo.com/?client_id=" + clientId + "&redirect_uri=" + redirectUri + "&response_type=code&state=" + state;
        return redirect(url);
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> monzoOAuthCallback(String code, String state, final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        if (code == null || code.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Missing OAuth authorization code from Monzo."));
        }

        try {
            String clientId = monzoBankSyncProvider.getClientId();
            String clientSecret = monzoBankSyncProvider.getClientSecret();
            String redirectUri = monzoBankSyncProvider.getRedirectUri();

            String formBody = "grant_type=authorization_code" +
                    "&client_id=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) +
                    "&client_secret=" + URLEncoder.encode(clientSecret, StandardCharsets.UTF_8) +
                    "&redirect_uri=" + URLEncoder.encode(redirectUri, StandardCharsets.UTF_8) +
                    "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8);

            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.monzo.com/oauth2/token"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formBody))
                    .build();

            return client.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .thenComposeAsync(response -> {
                        if (response.statusCode() != 200) {
                            return CompletableFuture.completedFuture(
                                    redirect(routes.BankSyncController.index())
                                            .flashing("error", "Monzo token exchange error: " + response.body()));
                        }
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode json = mapper.readTree(response.body());
                            String accessToken = json.path("access_token").asText();
                            String refreshToken = json.path("refresh_token").asText(null);
                            String monzoUserId = json.path("user_id").asText("monzo-user");
                            long expiresIn = json.path("expires_in").asLong(21600L);

                            MonzoConnection conn = new MonzoConnection(
                                    sup.getUserId(),
                                    accessToken,
                                    refreshToken,
                                    monzoUserId,
                                    System.currentTimeMillis() + (expiresIn * 1000L)
                            );

                            return monzoConnectionRepository.save(conn).thenApplyAsync(saved -> {
                                return redirect(routes.BankSyncController.index())
                                        .flashing("info", "Monzo connected via OAuth! Approve data access on your Monzo mobile app if prompted.");
                            }, ec.current());
                        } catch (Exception e) {
                            return CompletableFuture.completedFuture(
                                    redirect(routes.BankSyncController.index())
                                            .flashing("error", "Failed to parse Monzo OAuth response: " + e.getMessage()));
                        }
                    }, ec.current());
        } catch (Exception e) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "OAuth failure: " + e.getMessage()));
        }
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> disconnectMonzo(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        return monzoConnectionRepository.deleteByUserId(sup.getUserId())
                .thenApplyAsync(deleted -> redirect(routes.BankSyncController.index())
                        .flashing("info", "Disconnected Monzo integration."), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> uploadStatement(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);

        String accountIdStr = form.get("accountId");
        String csvContent = form.get("csvContent");
        boolean rewriteHistory = "true".equalsIgnoreCase(form.get("rewriteHistory"));

        if (accountIdStr == null || accountIdStr.trim().isEmpty() || csvContent == null || csvContent.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Account and CSV statement data are required."));
        }

        try {
            Long accountId = Long.parseLong(accountIdStr);
            return statementParserService.importStatement(sup.getUserId(), accountId, csvContent, rewriteHistory)
                    .thenApplyAsync(res -> {
                        if (res.isSuccess()) {
                            return redirect(routes.BankSyncController.index()).flashing("info", res.getMessage());
                        } else {
                            return redirect(routes.BankSyncController.index()).flashing("error", res.getMessage());
                        }
                    }, ec.current());
        } catch (NumberFormatException e) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Invalid account ID."));
        }
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> toggleAccountLedgerMode(Long id, final Http.Request request) {
        return accountRepository.toggleLedgerMode(id).thenApplyAsync(acc -> {
            if (acc == null) {
                return redirect(routes.BankSyncController.index()).flashing("error", "Account not found");
            }
            String modeName = acc.getLedgerMode() == Account.LedgerMode.TBL ? "Transaction-Based Ledger (TBL / Accurate Mode)" : "Available-Based Ledger (ABL / Balance Delta)";
            return redirect(routes.BankSyncController.index()).flashing(
                    "info",
                    "Switched " + acc.getName() + " to " + modeName + ". All historical balances and transactions remain 100% preserved.");
        }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> transactions(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        return bankTransactionRepository.listRecent(sup.getUserId(), 100)
                .thenApplyAsync(stream -> ok(toJson(stream.collect(Collectors.toList()))), ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> clearAccountTransactions(Long id, final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        return bankTransactionRepository.deleteForAccount(id)
                .thenApplyAsync(deleted -> redirect(routes.BankSyncController.index())
                        .flashing("info", "Deleted " + deleted + " transactions for account."), ec.current());
    }
}
