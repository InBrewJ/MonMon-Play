package controllers;

import helpers.ModelHelpers;
import helpers.UserHelpers;
import models.Account;
import models.AccountRepository;
import models.BankTransaction;
import models.BankTransactionRepository;
import models.PlaidItem;
import models.PlaidItemRepository;
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
import services.PlaidBankSyncProvider;
import services.StatementParserService;
import viewModels.SimpleUserProfile;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;

import static helpers.ModelHelpers.repoListToList;
import static play.libs.Json.toJson;
import static play.libs.Scala.asScala;

public class BankSyncController extends Controller {

    private final AccountRepository accountRepository;
    private final BankTransactionRepository bankTransactionRepository;
    private final PlaidItemRepository plaidItemRepository;
    private final MockBankSyncProvider mockBankSyncProvider;
    private final PlaidBankSyncProvider plaidBankSyncProvider;
    private final StatementParserService statementParserService;
    private final FormFactory formFactory;
    private final MessagesApi messagesApi;
    private final HttpExecutionContext ec;

    @Inject
    private SessionStore playSessionStore;

    @Inject
    public BankSyncController(AccountRepository accountRepository,
                              BankTransactionRepository bankTransactionRepository,
                              PlaidItemRepository plaidItemRepository,
                              MockBankSyncProvider mockBankSyncProvider,
                              PlaidBankSyncProvider plaidBankSyncProvider,
                              StatementParserService statementParserService,
                              FormFactory formFactory,
                              MessagesApi messagesApi,
                              HttpExecutionContext ec) {
        this.accountRepository = accountRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.plaidItemRepository = plaidItemRepository;
        this.mockBankSyncProvider = mockBankSyncProvider;
        this.plaidBankSyncProvider = plaidBankSyncProvider;
        this.statementParserService = statementParserService;
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.ec = ec;
    }

    private BankSyncService resolveProvider(String providerName) {
        if ("PLAID".equalsIgnoreCase(providerName)) {
            return plaidBankSyncProvider;
        }
        return mockBankSyncProvider;
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> index(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);

        return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
            List<Account> accounts = accStream.collect(Collectors.toList());
            return bankTransactionRepository.listRecent(sup.getUserId(), 100).thenComposeAsync(txStream -> {
                List<BankTransaction> txList = txStream.collect(Collectors.toList());
                return plaidItemRepository.findByUserId(sup.getUserId()).thenApplyAsync(linkedItems -> {
                    boolean isPlaidConfigured = plaidBankSyncProvider.isConfigured();
                    String providerName = isPlaidConfigured ? "PLAID" : "MOCK";

                    return ok(views.html.bankSync.render(
                            asScala(accounts),
                            asScala(txList),
                            asScala(linkedItems),
                            providerName,
                            isPlaidConfigured,
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
    public CompletionStage<Result> createPlaidLinkToken(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        return plaidBankSyncProvider.createLinkToken(sup.getUserId())
                .thenApplyAsync(linkToken -> {
                    com.fasterxml.jackson.databind.node.ObjectNode json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    json.put("link_token", linkToken);
                    return ok(json);
                }, ec.current())
                .exceptionally(err -> {
                    com.fasterxml.jackson.databind.node.ObjectNode json = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    json.put("error", err.getMessage());
                    return internalServerError(json);
                });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> exchangePlaidPublicToken(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        com.fasterxml.jackson.databind.JsonNode json = request.body().asJson();
        if (json == null || !json.has("public_token")) {
            return CompletableFuture.completedFuture(badRequest("Missing public_token in payload"));
        }
        String publicToken = json.path("public_token").asText();
        String institutionId = json.path("institution_id").asText("unknown");
        String institutionName = json.path("institution_name").asText("Bank Account");

        return plaidBankSyncProvider.exchangePublicToken(sup.getUserId(), publicToken, institutionId, institutionName)
                .thenComposeAsync(item -> {
                    return plaidBankSyncProvider.syncPlaidTransactions(sup.getUserId(), null, item.getAccessToken(), null)
                            .thenApplyAsync(res -> {
                                com.fasterxml.jackson.databind.node.ObjectNode resp = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                                resp.put("success", true);
                                resp.put("institution", institutionName);
                                resp.put("message", res.getMessage());
                                return ok(resp);
                            }, ec.current());
                }, ec.current())
                .exceptionally(err -> {
                    com.fasterxml.jackson.databind.node.ObjectNode resp = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
                    resp.put("success", false);
                    resp.put("error", err.getMessage());
                    return internalServerError(resp);
                });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> deletePlaidItem(Long id, final Http.Request request) {
        return plaidItemRepository.delete(id)
                .thenApplyAsync(deleted -> redirect(routes.BankSyncController.index())
                        .flashing("info", "Unlinked Plaid bank connection."), ec.current());
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
