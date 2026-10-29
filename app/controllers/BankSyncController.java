package controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import helpers.UserHelpers;
import models.*;
import org.pac4j.core.context.session.SessionStore;
import org.pac4j.play.java.Secure;
import play.data.DynamicForm;
import play.data.FormFactory;
import play.i18n.MessagesApi;
import play.libs.Json;
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
import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;
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
    private final PendingStatementRepository pendingStatementRepository;
    private final BalanceRepository balanceRepository;
    private final Config config;
    private final HttpClient httpClient;

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
                              HttpExecutionContext ec,
                              PendingStatementRepository pendingStatementRepository,
                              BalanceRepository balanceRepository,
                              Config config) {
        this.accountRepository = accountRepository;
        this.bankTransactionRepository = bankTransactionRepository;
        this.monzoConnectionRepository = monzoConnectionRepository;
        this.mockBankSyncProvider = mockBankSyncProvider;
        this.monzoBankSyncProvider = monzoBankSyncProvider;
        this.statementParserService = statementParserService;
        this.formFactory = formFactory;
        this.messagesApi = messagesApi;
        this.ec = ec;
        this.pendingStatementRepository = pendingStatementRepository;
        this.balanceRepository = balanceRepository;
        this.config = config;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    // Overloaded constructor for backwards compatibility with tests
    public BankSyncController(AccountRepository accountRepository,
                              BankTransactionRepository bankTransactionRepository,
                              MonzoConnectionRepository monzoConnectionRepository,
                              MockBankSyncProvider mockBankSyncProvider,
                              MonzoBankSyncProvider monzoBankSyncProvider,
                              StatementParserService statementParserService,
                              FormFactory formFactory,
                              MessagesApi messagesApi,
                              HttpExecutionContext ec) {
        this(accountRepository, bankTransactionRepository, monzoConnectionRepository,
                mockBankSyncProvider, monzoBankSyncProvider, statementParserService,
                formFactory, messagesApi, ec, null, null, null);
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
                return monzoConnectionRepository.findByUserId(sup.getUserId()).thenComposeAsync(monzoConnOpt -> {
                    boolean isMonzoConnected = monzoConnOpt.isPresent();
                    String providerName = isMonzoConnected ? "MONZO" : "MOCK";

                    CompletionStage<List<PendingStatement>> pendingStage = (pendingStatementRepository != null)
                            ? pendingStatementRepository.listPending(sup.getUserId())
                            : CompletableFuture.completedFuture(Collections.emptyList());

                    return pendingStage.thenApplyAsync(pendingStatements -> {
                        return ok(views.html.bankSync.render(
                                asScala(accounts),
                                asScala(txList),
                                monzoConnOpt.orElse(null),
                                providerName,
                                isMonzoConnected,
                                accounts.size(),
                                txList.size(),
                                sup,
                                asScala(pendingStatements),
                                request,
                                messagesApi.preferred(request)
                        ));
                    }, ec.current());
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

    public CompletionStage<Result> handleMonzoWebhook(final Http.Request request) {
        String userId = request.queryString("userId").orElse(null);
        JsonNode json = request.body().asJson();
        if (json == null) {
            return CompletableFuture.completedFuture(ok(Json.newObject().put("status", "ok")));
        }

        // Check if this is a statement payload from LegMon
        boolean isStatement = request.queryString("statement").isPresent()
                || json.has("statement")
                || "statement.extracted".equalsIgnoreCase(json.path("type").asText());

        if (isStatement) {
            return processStatementWebhook(userId, json);
        }

        return monzoBankSyncProvider.handleWebhookEvent(userId, json)
                .thenApplyAsync(success -> ok(Json.newObject().put("status", "ok")), ec.current())
                .exceptionally(err -> {
                    System.err.println("Webhook handling exception: " + err.getMessage());
                    return ok(Json.newObject().put("status", "ok"));
                });
    }

    public CompletionStage<Result> handleStatementWebhook(final Http.Request request) {
        String userId = request.queryString("userId").orElse(null);
        JsonNode json = request.body().asJson();
        if (json == null) {
            return CompletableFuture.completedFuture(ok(Json.newObject().put("status", "ok")));
        }
        return processStatementWebhook(userId, json);
    }

    private CompletionStage<Result> processStatementWebhook(String queryUserId, JsonNode json) {
        if (pendingStatementRepository == null) {
            return CompletableFuture.completedFuture(ok(Json.newObject().put("status", "ok")));
        }

        String userId = (queryUserId != null && !queryUserId.trim().isEmpty())
                ? queryUserId.trim()
                : json.path("userId").asText("default-user");

        String jobId = json.path("jobId").asText("stmt_" + System.currentTimeMillis());
        String bank = json.path("bank").asText("Unknown Bank");
        String startDate = json.path("startDate").asText(LocalDate.now().minusDays(30).toString());
        String endDate = json.path("endDate").asText(LocalDate.now().toString());
        float openingBalance = (float) json.path("openingBalance").asDouble(0.0);
        float closingBalance = (float) json.path("closingBalance").asDouble(0.0);

        Long targetAccountId = (json.has("targetAccountId") && !json.get("targetAccountId").isNull())
                ? json.get("targetAccountId").asLong()
                : null;

        int txCount = json.path("transactions").isArray() ? json.path("transactions").size() : 0;

        long startSec = parseEpochSeconds(startDate, true);
        long endSec = parseEpochSeconds(endDate, false);

        PendingStatement stmt = new PendingStatement(
                userId, jobId, bank, startDate, endDate, startSec, endSec,
                openingBalance, closingBalance, txCount, json.toString()
        );
        if (targetAccountId != null) {
            stmt.setMatchedAccountId(targetAccountId);
        }

        return pendingStatementRepository.save(stmt).thenApplyAsync(saved -> {
            ObjectNode res = Json.newObject();
            res.put("status", "ok");
            res.put("statementId", saved.getId());
            res.put("jobId", jobId);
            res.put("bank", bank);
            return ok(res);
        }, ec.current()).exceptionally(err -> {
            System.err.println("Error saving pending statement from webhook: " + err.getMessage());
            return ok(Json.newObject().put("status", "ok"));
        });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> uploadStatement(final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        Http.MultipartFormData<play.libs.Files.TemporaryFile> body = request.body().asMultipartFormData();
        if (body == null) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "No file uploaded. Please select a PDF statement."));
        }

        Http.MultipartFormData.FilePart<play.libs.Files.TemporaryFile> pdfPart = body.getFile("pdfFile");
        if (pdfPart == null) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Missing 'pdfFile' in upload."));
        }

        play.libs.Files.TemporaryFile tempFile = pdfPart.getRef();
        File file = tempFile.path().toFile();
        String filename = pdfPart.getFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".pdf")) {
            file.delete();
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Only PDF bank statement files are supported."));
        }

        String accountIdStr = null;
        if (body.asFormUrlEncoded().containsKey("accountId")) {
            String[] accVals = body.asFormUrlEncoded().get("accountId");
            if (accVals.length > 0 && !accVals[0].trim().isEmpty()) {
                accountIdStr = accVals[0].trim();
            }
        }

        byte[] fileBytes;
        try {
            fileBytes = java.nio.file.Files.readAllBytes(file.toPath());
        } catch (Exception e) {
            file.delete();
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Could not read uploaded PDF file: " + e.getMessage()));
        } finally {
            // Delete temporary upload file immediately as required
            file.delete();
        }

        String legmonUrl = (config != null && config.hasPath("legmon.url"))
                ? config.getString("legmon.url")
                : "http://localhost:9005";

        String hostHeader = request.host();
        String scheme = request.secure() ? "https" : "http";
        if (request.header("X-Forwarded-Proto").isPresent()) {
            scheme = request.header("X-Forwarded-Proto").get();
        }
        String baseUrl = scheme + "://" + hostHeader;
        String callbackUrl = baseUrl + "/api/monzo/webhook?statement=true&userId=" + URLEncoder.encode(sup.getUserId(), StandardCharsets.UTF_8);

        StringBuilder processUri = new StringBuilder(legmonUrl + "/api/process?userId=" + URLEncoder.encode(sup.getUserId(), StandardCharsets.UTF_8));
        processUri.append("&callbackUrl=").append(URLEncoder.encode(callbackUrl, StandardCharsets.UTF_8));
        if (accountIdStr != null && !accountIdStr.isEmpty()) {
            processUri.append("&accountId=").append(accountIdStr);
        }

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(processUri.toString()))
                .header("Content-Type", "application/pdf")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofByteArray(fileBytes))
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() == 200 || resp.statusCode() == 202) {
                        return redirect(routes.BankSyncController.index())
                                .flashing("info", "PDF statement '" + filename + "' uploaded to LegMon! OCR & Mistral extraction kicked off. Review extracted bank and match your account below.");
                    } else {
                        return redirect(routes.BankSyncController.index())
                                .flashing("error", "LegMon service returned HTTP " + resp.statusCode() + ": " + resp.body());
                    }
                }, ec.current())
                .exceptionally(err -> {
                    return redirect(routes.BankSyncController.index())
                            .flashing("error", "Failed to reach LegMon service at " + legmonUrl + ": " + err.getMessage() + ". Check that LegMon Docker container is running.");
                });
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> confirmStatement(Long id, final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        DynamicForm form = formFactory.form().bindFromRequest(request);
        String accountIdStr = form.get("accountId");
        String anchorBalanceStr = form.get("anchorBalance");
        String anchorDateStr = form.get("anchorDate");
        if (accountIdStr == null || accountIdStr.trim().isEmpty()) {
            return CompletableFuture.completedFuture(
                    redirect(routes.BankSyncController.index()).flashing("error", "Please select an account to match with this statement."));
        }
        final Long accountId = Long.parseLong(accountIdStr.trim());

        return pendingStatementRepository.findById(id).thenComposeAsync(optStmt -> {
            if (optStmt.isEmpty()) {
                return CompletableFuture.completedFuture(
                        redirect(routes.BankSyncController.index()).flashing("error", "Statement not found."));
            }
            PendingStatement stmt = optStmt.get();
            if (!sup.getUserId().equals(stmt.getUserId())) {
                return CompletableFuture.completedFuture(
                        redirect(routes.BankSyncController.index()).flashing("error", "Unauthorized statement access."));
            }

            return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
                Optional<Account> matchedAccOpt = accStream.filter(a -> a.getId().equals(accountId)).findFirst();
                if (matchedAccOpt.isEmpty()) {
                    return CompletableFuture.completedFuture(
                            redirect(routes.BankSyncController.index()).flashing("error", "Selected account not found."));
                }
                Account targetAccount = matchedAccOpt.get();

                // 1. Parse transactions from statement payload
                List<BankTransaction> txsToSave = new ArrayList<>();
                try {
                    JsonNode payloadJson = Json.parse(stmt.getRawPayload());
                    JsonNode txArray = payloadJson.path("transactions");
                    if (txArray.isArray()) {
                        for (JsonNode txItem : txArray) {
                            JsonNode data = txItem.has("data") ? txItem.get("data") : txItem;
                            String txId = data.path("id").asText("legmon_tx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                            long pence = data.path("amount").asLong(0L);
                            float amount = (data.has("amount") && data.get("amount").isFloatingPointNumber())
                                    ? (float) data.get("amount").asDouble()
                                    : (Math.abs(pence) / 100.0f * (pence > 0 ? -1.0f : 1.0f));

                            String created = data.path("created").asText(LocalDate.now().toString());
                            long epochSec = System.currentTimeMillis() / 1000L;
                            String dateStr = stmt.getEndDate();
                            try {
                                if (created.contains("T")) {
                                    Instant inst = Instant.parse(created);
                                    epochSec = inst.getEpochSecond();
                                    dateStr = inst.atZone(ZoneOffset.UTC).toLocalDate().toString();
                                } else {
                                    LocalDate ld = LocalDate.parse(created);
                                    epochSec = ld.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
                                    dateStr = ld.toString();
                                }
                            } catch (Exception ignored) {
                            }

                            String desc = data.path("description").asText("Statement item");
                            String merchant = data.path("merchant").path("name").asText(desc);
                            String category = data.path("category").asText("General");

                            BankTransaction tx = new BankTransaction(
                                    sup.getUserId(),
                                    accountId,
                                    txId,
                                    epochSec,
                                    dateStr,
                                    amount,
                                    desc,
                                    merchant,
                                    category,
                                    "STATEMENT",
                                    false
                            );
                            try {
                                tx.setRawData(Json.stringify(data));
                            } catch (Exception ignored) {
                            }
                            txsToSave.add(tx);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Could not parse transactions from statement: " + e.getMessage());
                }

                // 2. Save all transactions
                return bankTransactionRepository.saveAll(txsToSave).thenComposeAsync(savedCount -> {
                    boolean isTxListOnly = helpers.TransactionBalanceReconstructor.isTransactionListOnly(stmt);
                    Float userAnchorVal = null;
                    if (anchorBalanceStr != null && !anchorBalanceStr.trim().isEmpty()) {
                        try {
                            userAnchorVal = Float.parseFloat(anchorBalanceStr.trim());
                        } catch (Exception ignored) {}
                    }

                    if (userAnchorVal != null) {
                        long anchorTs = (anchorDateStr != null && !anchorDateStr.trim().isEmpty())
                                ? parseEpochSeconds(anchorDateStr.trim(), false)
                                : stmt.getEndTimestamp();
                        List<helpers.TransactionBalanceReconstructor.TransactionDelta> deltas =
                                helpers.TransactionBalanceReconstructor.fromBankTransactions(txsToSave);
                        helpers.TransactionBalanceReconstructor.ReconstructionResult rec =
                                helpers.TransactionBalanceReconstructor.reconstruct(
                                        deltas, anchorTs, userAnchorVal, stmt.getStartTimestamp(), stmt.getEndTimestamp());
                        stmt.setOpeningBalance(rec.getOpeningBalance());
                        stmt.setClosingBalance(rec.getClosingBalance());

                        return applyBalancesAndFinalize(stmt, targetAccount, accountId, sup.getUserId(), savedCount,
                                "Transaction list matched to '" + targetAccount.getName() + "'. Reconstructed balances from anchor £" + String.format("%.2f", userAnchorVal) + " (closing: £" + String.format("%.2f", stmt.getClosingBalance()) + ") and imported " + savedCount + " transactions.");
                    } else if (isTxListOnly) {
                        // Check if existing balance can be discovered from balanceRepository
                        return balanceRepository.list(sup.getUserId()).thenComposeAsync(balancesStream -> {
                            List<Balance> existingAccBalances = balancesStream
                                    .filter(b -> b.getAccount() != null && accountId.equals(b.getAccount().getId()))
                                    .collect(Collectors.toList());
                            Optional<Balance> autoAnchor = helpers.TransactionBalanceReconstructor.findAnchorBalance(
                                    existingAccBalances, stmt.getStartTimestamp(), stmt.getEndTimestamp());

                            if (autoAnchor.isPresent()) {
                                Balance a = autoAnchor.get();
                                List<helpers.TransactionBalanceReconstructor.TransactionDelta> deltas =
                                        helpers.TransactionBalanceReconstructor.fromBankTransactions(txsToSave);
                                helpers.TransactionBalanceReconstructor.ReconstructionResult rec =
                                        helpers.TransactionBalanceReconstructor.reconstruct(
                                                deltas, a.getTimestamp(), a.getValue(), stmt.getStartTimestamp(), stmt.getEndTimestamp());
                                stmt.setOpeningBalance(rec.getOpeningBalance());
                                stmt.setClosingBalance(rec.getClosingBalance());
                                return applyBalancesAndFinalize(stmt, targetAccount, accountId, sup.getUserId(), savedCount,
                                        "Transaction list matched to '" + targetAccount.getName() + "'. Auto-reconstructed balances from existing anchor balance of £" + String.format("%.2f", a.getValue()) + " (closing: £" + String.format("%.2f", stmt.getClosingBalance()) + ") and imported " + savedCount + " transactions.");
                            } else {
                                // No anchor balance provided or found. Import transactions but do NOT dangerously overwrite balances with 0!
                                stmt.setStatus("APPLIED");
                                stmt.setMatchedAccountId(accountId);
                                stmt.setAppliedAt(System.currentTimeMillis() / 1000L);
                                return pendingStatementRepository.save(stmt).thenApplyAsync(savedStmt -> {
                                    return redirect(routes.BankSyncController.index())
                                            .flashing("info", "Transaction list matched to '" + targetAccount.getName() + "'! Imported " + savedCount + " transactions. No anchor balance was provided, so existing balances were preserved without overwrite. Enter an anchor balance at any date to reconstruct full balances.");
                                }, ec.current());
                            }
                        }, ec.current());
                    } else {
                        // Standard statement with verified closing balance
                        return applyBalancesAndFinalize(stmt, targetAccount, accountId, sup.getUserId(), savedCount,
                                "Statement matched to '" + targetAccount.getName() + "'! Dangerously overwritten ABL balances for " + stmt.getStartDate() + " to " + stmt.getEndDate() + " (closing: £" + String.format("%.2f", stmt.getClosingBalance()) + ") and imported " + savedCount + " transactions.");
                    }
                }, ec.current());
            }, ec.current());
        }, ec.current());
    }

    private CompletionStage<Result> applyBalancesAndFinalize(PendingStatement stmt, Account targetAccount, Long accountId, String userId, int savedCount, String flashMessage) {
        return balanceRepository.deleteForAccountBetween(accountId, userId, stmt.getStartTimestamp(), stmt.getEndTimestamp())
                .thenComposeAsync(deletedBalancesCount -> {
                    Balance closingBal = new Balance();
                    closingBal.setUserId(userId);
                    closingBal.setAccount(targetAccount);
                    closingBal.setValue(stmt.getClosingBalance());
                    closingBal.setTimestamp(stmt.getEndTimestamp());

                    return balanceRepository.add(closingBal).thenComposeAsync(addedClosing -> {
                        CompletableFuture<Boolean> openFut = CompletableFuture.completedFuture(true);
                        if (stmt.getOpeningBalance() != null && stmt.getStartTimestamp() < stmt.getEndTimestamp()) {
                            Balance openBal = new Balance();
                            openBal.setUserId(userId);
                            openBal.setAccount(targetAccount);
                            openBal.setValue(stmt.getOpeningBalance());
                            openBal.setTimestamp(stmt.getStartTimestamp());
                            openFut = balanceRepository.add(openBal).thenApply(b -> true).toCompletableFuture();
                        }

                        return openFut.thenComposeAsync(v -> {
                            stmt.setStatus("APPLIED");
                            stmt.setMatchedAccountId(accountId);
                            stmt.setAppliedAt(System.currentTimeMillis() / 1000L);
                            return pendingStatementRepository.save(stmt).thenApplyAsync(savedStmt -> {
                                return redirect(routes.BankSyncController.index()).flashing("info", flashMessage);
                            }, ec.current());
                        }, ec.current());
                    }, ec.current());
                }, ec.current());
    }

    @Secure(clients = "OidcClient", authorizers = "isAuthenticated")
    public CompletionStage<Result> discardStatement(Long id, final Http.Request request) {
        SimpleUserProfile sup = UserHelpers.getSimpleUserProfile(playSessionStore, request);
        return pendingStatementRepository.findById(id).thenComposeAsync(optStmt -> {
            if (optStmt.isEmpty()) {
                return CompletableFuture.completedFuture(
                        redirect(routes.BankSyncController.index()).flashing("error", "Statement not found."));
            }
            PendingStatement stmt = optStmt.get();
            if (!sup.getUserId().equals(stmt.getUserId())) {
                return CompletableFuture.completedFuture(
                        redirect(routes.BankSyncController.index()).flashing("error", "Unauthorized statement access."));
            }
            stmt.setStatus("DISCARDED");
            return pendingStatementRepository.save(stmt).thenApplyAsync(saved -> {
                return redirect(routes.BankSyncController.index()).flashing("info", "Statement discarded.");
            }, ec.current());
        }, ec.current());
    }

    public CompletionStage<Result> legmonStatus(final Http.Request request) {
        String legmonUrl = (config != null && config.hasPath("legmon.url"))
                ? config.getString("legmon.url")
                : "http://localhost:9005";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(legmonUrl + "/status"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() == 200) {
                        return ok(Json.parse(resp.body()));
                    } else {
                        ObjectNode obj = Json.newObject();
                        obj.put("status", "warning");
                        obj.put("service", "LegMon");
                        obj.put("error", "HTTP " + resp.statusCode());
                        return ok(obj);
                    }
                }, ec.current())
                .exceptionally(err -> {
                    ObjectNode obj = Json.newObject();
                    obj.put("status", "offline");
                    obj.put("service", "LegMon");
                    obj.put("error", err.getMessage());
                    return ok(obj);
                });
    }

    public CompletionStage<Result> legmonLogs(final Http.Request request) {
        String legmonUrl = (config != null && config.hasPath("legmon.url"))
                ? config.getString("legmon.url")
                : "http://localhost:9005";

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(legmonUrl + "/api/logs"))
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();

        return httpClient.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync(resp -> {
                    if (resp.statusCode() == 200) {
                        return ok(Json.parse(resp.body()));
                    } else {
                        ObjectNode obj = Json.newObject();
                        obj.put("status", "error");
                        obj.put("error", "HTTP " + resp.statusCode());
                        return ok(obj);
                    }
                }, ec.current())
                .exceptionally(err -> {
                    ObjectNode obj = Json.newObject();
                    obj.put("status", "offline");
                    obj.put("error", err.getMessage());
                    obj.set("logs", Json.newArray());
                    return ok(obj);
                });
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

        return monzoBankSyncProvider.fetchAccounts(cleanToken).thenComposeAsync(monzoAccounts -> {
            MonzoConnection conn = new MonzoConnection(
                    sup.getUserId(),
                    cleanToken,
                    null,
                    "monzo-user",
                    System.currentTimeMillis() + (86400L * 365L * 1000L)
            );
            return monzoConnectionRepository.save(conn).thenComposeAsync(saved -> {
                String hostHeader = request.host();
                String scheme = request.secure() ? "https" : "http";
                if (request.header("X-Forwarded-Proto").isPresent()) {
                    scheme = request.header("X-Forwarded-Proto").get();
                }
                String baseUrl = scheme + "://" + hostHeader;

                return monzoBankSyncProvider.registerWebhooks(saved, baseUrl).thenComposeAsync(registeredConn -> {
                    return accountRepository.list(sup.getUserId()).thenComposeAsync(accStream -> {
                        List<Account> accounts = accStream.collect(Collectors.toList());
                        return monzoBankSyncProvider.syncAllUserAccounts(sup.getUserId(), accounts, 30).thenApplyAsync(res -> {
                            String webhookMsg = registeredConn.hasActiveWebhooks()
                                    ? " Real-time webhooks registered successfully!"
                                    : " Real-time push configured.";
                            return redirect(routes.BankSyncController.index())
                                    .flashing("info", "Monzo connected! Discovered " + monzoAccounts.size() + " accounts (including Debit & Monzo Flex)." + webhookMsg + " Initial balances loaded.");
                        }, ec.current());
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
                            JsonNode json = mapper.readTree(response.body());
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

    private long parseEpochSeconds(String dateStr, boolean startOfDay) {
        try {
            LocalDate ld = LocalDate.parse(dateStr);
            if (startOfDay) {
                return ld.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
            } else {
                return ld.atTime(23, 59, 59).toEpochSecond(ZoneOffset.UTC);
            }
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000L;
        }
    }
}
