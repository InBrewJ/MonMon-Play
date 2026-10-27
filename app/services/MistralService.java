package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import models.Account;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import viewModels.SpendingPlan;
import viewModels.Spog;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MistralService {
    private static final Logger logger = LoggerFactory.getLogger(MistralService.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final Config config;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    private static class CacheEntry {
        final String text;
        final long timestamp;
        CacheEntry(String text, long timestamp) {
            this.text = text;
            this.timestamp = timestamp;
        }
    }

    private final Map<String, CacheEntry> userAdviceCache = new ConcurrentHashMap<>();
    private final Map<String, CacheEntry> nomMonCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 31L * 24L * 60L * 60L * 1000L; // 31 days TTL

    @Inject
    public MistralService(Config config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public MistralService(Config config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
        logConfigStatus();
    }

    private void log(String level, String msg) {
        String timestamp = ISO_FORMATTER.format(Instant.now());
        String formatted = String.format("[%s] [MistralService] [%s] %s", timestamp, level, msg);
        if ("ERROR".equalsIgnoreCase(level)) {
            logger.error(formatted);
            System.err.println(formatted);
        } else if ("WARN".equalsIgnoreCase(level)) {
            logger.warn(formatted);
            System.out.println(formatted);
        } else {
            logger.info(formatted);
            System.out.println(formatted);
        }
    }

    private void logConfigStatus() {
        String key = getApiKey();
        if (key.isEmpty()) {
            log("WARN", "Mistral API key is NOT configured. Neither 'mistral.apiKey' in conf nor 'MISTRAL_API_KEY' in environment found.");
        } else {
            String source = (config != null && config.hasPath("mistral.apiKey") && !config.getString("mistral.apiKey").isBlank())
                    ? "conf/application.conf (mistral.apiKey)"
                    : "environment variable (MISTRAL_API_KEY)";
            log("INFO", String.format("Mistral API configured successfully from %s. Key=%s, Model=%s",
                    source, maskKey(key), getModel()));
        }
    }

    public static String maskKey(String key) {
        if (key == null || key.isBlank()) return "[NONE]";
        String trimmed = key.trim();
        if (trimmed.length() <= 8) return trimmed.substring(0, Math.min(2, trimmed.length())) + "***";
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4) + " (len=" + trimmed.length() + ")";
    }

    public boolean isConfigured() {
        String key = getApiKey();
        return key != null && !key.trim().isEmpty();
    }

    public String getApiKey() {
        if (config != null && config.hasPath("mistral.apiKey") && !config.getString("mistral.apiKey").isBlank()) {
            return config.getString("mistral.apiKey").trim();
        }
        String env = System.getenv("MISTRAL_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "";
    }

    public String getModel() {
        if (config != null && config.hasPath("mistral.model") && !config.getString("mistral.model").isBlank()) {
            return config.getString("mistral.model").trim();
        }
        return "mistral-small-latest";
    }

    public void clearCache(String userId) {
        if (userId != null) {
            userAdviceCache.remove(userId);
            log("INFO", "Cleared advice cache for user=" + userId);
        } else {
            userAdviceCache.clear();
            log("INFO", "Cleared entire advice cache");
        }
    }

    public Long getLastSpokeTimestamp(String userId) {
        if (userId == null) return null;
        CacheEntry cached = userAdviceCache.get(userId);
        return cached != null ? cached.timestamp : null;
    }

    public Long getNomMonTimestamp(String userId) {
        if (userId == null) return null;
        CacheEntry cached = nomMonCache.get(userId);
        return cached != null ? cached.timestamp : null;
    }

    public void clearNomMonCache(String userId) {
        if (userId != null) {
            nomMonCache.remove(userId);
            log("INFO", "Cleared NomMon cache for user=" + userId);
        } else {
            nomMonCache.clear();
            log("INFO", "Cleared entire NomMon cache");
        }
    }

    public static String buildFinancialSnapshotPrompt(Spog spogVm) {
        if (spogVm == null) {
            return "No financial data available.";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Here is the user's current financial snapshot from their MonMon SPOG dashboard:\n\n");
        sb.append("- As of date: ").append(spogVm.getToday()).append("\n");
        sb.append("- Days until next payday: ").append(spogVm.getDaysUntilNextPayday())
                .append(" days (Next payday: ").append(spogVm.getNextPayDate()).append(")\n");
        sb.append("- Total days in this pay cycle: ").append(spogVm.getDaysBetweenPaydays()).append(" days\n");
        sb.append("- Days elapsed since last payday: ").append(spogVm.getDaysSinceLastPayday()).append(" days\n\n");

        sb.append("Income & Allowances:\n");
        sb.append("- Monthly Income / Takehome: £").append(spogVm.getIncomingTotal()).append("\n");
        sb.append("- Total Outgoings (bills/rent/subscriptions): £").append(spogVm.getOutgoingTotal()).append("\n");
        sb.append("- Max Monthly Spending Allowance: £").append(spogVm.getSurplus()).append("\n");
        sb.append("- Max Daily Allowance: £").append(spogVm.getMaxPerDay()).append("/day\n");
        sb.append("- Max Weekly Allowance: £").append(spogVm.getMaxPerWeek()).append("/week\n\n");

        sb.append("Current Spending & Baseline Status:\n");
        sb.append("- Total spent this month so far: £").append(spogVm.getSpentThisMonth()).append("\n");
        sb.append("- Actual average daily spend: £").append(spogVm.getActualDailySpend()).append("/day\n");
        sb.append("- Daily overspend/underspend vs target: £").append(spogVm.getDailyOverspend()).append("/day\n");
        if (spogVm.isBehindBaseline()) {
            sb.append("- Baseline status: BEHIND baseline by £").append(spogVm.getAbsoluteBaselineDiffTotal()).append(" (over budget!)\n");
        } else {
            sb.append("- Baseline status: AHEAD of baseline with £").append(spogVm.getAbsoluteBaselineDiffTotal()).append(" surplus\n");
        }
        sb.append("- Outgoings already paid this cycle: £").append(spogVm.getCompletedOutgoings()).append("\n");
        sb.append("- Outgoings still pending before payday: £").append(spogVm.getPendingOutgoings()).append("\n\n");

        sb.append("Balances & Pot:\n");
        sb.append("- Left per day in designated Monthly Pot: £").append(spogVm.getMonthlyPotLeftPerDay()).append("/day\n");
        sb.append("- Total Available Debit: £").append(spogVm.getTotalAvailableDebit()).append("\n");
        sb.append("- Total Available Credit: £").append(spogVm.getTotalAvailableCredit()).append("\n");
        sb.append("- Total Funds: £").append(spogVm.getTotalFunds()).append("\n\n");

        sb.append("Credit & Debt:\n");
        sb.append("- Total Credit Debt Owed: £").append(spogVm.getCreditBalance()).append("\n");
        sb.append("- Total Credit Limit: £").append(spogVm.getCreditLimit()).append("\n");
        sb.append("- Credit Usage: ").append(spogVm.getCreditUsage()).append("%\n");
        sb.append("- Credit Available (limit remaining to spend): £").append(spogVm.getTotalAvailableCredit()).append("\n");
        if (spogVm.getLoanRemainingTotal() > 0 || spogVm.getLoanOriginalDrawdownTotal() > 0) {
            sb.append("- Loans Remaining: £").append(spogVm.getLoanRemainingTotal())
                    .append(" (Original drawdown: £").append(spogVm.getLoanOriginalDrawdownTotal()).append(")\n");
        }
        sb.append("\n");

        sb.append("Savings & Assets:\n");
        sb.append("- Liquid Savings Pot: £").append(spogVm.getLiquidSavingsPot()).append("\n");
        sb.append("- Total Savings (including Pension): £").append(spogVm.getSavingsPot()).append("\n");
        sb.append("- Pension Pot: £").append(spogVm.getPensionPot()).append("\n\n");

        if (spogVm.getAllAccounts() != null && !spogVm.getAllAccounts().isEmpty()) {
            sb.append("Accounts:\n");
            Map<Account, viewModels.AccountStatus> statusMap = spogVm.getAccountStatusMap();
            for (Account a : spogVm.getAllAccounts()) {
                if (a != null) {
                    viewModels.AccountStatus status = statusMap != null ? statusMap.get(a) : null;
                    if (a.getType() == Account.AccountType.CREDIT) {
                        double owed = status != null ? status.getBalanceWithLimits() : 0.0;
                        float limit = a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f;
                        double available = status != null ? status.getLatestBalance() : (double) a.getLatestBalanceValue();
                        sb.append("  • ").append(a.getName() != null ? a.getName() : "Credit Card")
                                .append(" (CREDIT): £").append(owed).append(" owed of £").append(limit).append(" limit (£")
                                .append(available).append(" available to spend)\n");
                    } else if (a.getType() == Account.AccountType.LOAN) {
                        sb.append("  • ").append(a.getName() != null ? a.getName() : "Loan")
                                .append(" (LOAN): £").append(a.getLatestBalanceValue()).append(" remaining of £")
                                .append(a.getAvailableLimit() != null ? a.getAvailableLimit() : 0f).append(" original\n");
                    } else {
                        sb.append("  • ").append(a.getName() != null ? a.getName() : "Account")
                                .append(" (").append(a.getType() != null ? a.getType() : "DEBIT").append("): £")
                                .append(a.getLatestBalanceValue()).append("\n");
                    }
                }
            }
            sb.append("\n");
        }

        if (spogVm.getSpendingPlan() != null) {
            SpendingPlan plan = spogVm.getSpendingPlan();
            sb.append("Spending Plan (").append(plan.getName()).append("):\n");
            sb.append("  • Planned Food: £").append(plan.getPlannedFoodSpend()).append(" | Spent: £").append(plan.getSpentFood()).append("\n");
            sb.append("  • Planned Fuel: £").append(plan.getPlannedFuelSpend()).append(" | Spent: £").append(plan.getSpentFuel()).append("\n");
            sb.append("  • Planned Transport: £").append(plan.getPlannedTransportSpend()).append(" | Spent: £").append(plan.getSpentTransport()).append("\n");
            sb.append("  • Planned Fun: £").append(plan.getPlannedFun()).append(" | Spent: £").append(plan.getSpentFun()).append("\n");
            sb.append("  • Remaining Essentials: £").append(plan.getRemainingEssentials()).append("\n");
            sb.append("  • Adjusted Fun Left Per Day: £").append(plan.getAdjustedFunLeftPerDay()).append("/day\n\n");
        }

        sb.append("Please analyze these finances as Ed in the Hemingway style. Give a clear appraisal of the reality and the exact actions required to reach payday.");
        return sb.toString();
    }

    public static String extractErrorMessage(String respBody) {
        if (respBody == null || respBody.isBlank()) return null;
        try {
            ObjectMapper om = new ObjectMapper();
            JsonNode root = om.readTree(respBody);
            if (root.has("message") && !root.path("message").asText().isBlank()) {
                return root.path("message").asText();
            }
            if (root.has("detail") && !root.path("detail").asText().isBlank()) {
                return root.path("detail").asText();
            }
            if (root.has("error") && root.path("error").has("message")) {
                return root.path("error").path("message").asText();
            }
        } catch (Exception ignored) {
        }
        return respBody.length() > 200 ? respBody.substring(0, 200) + "..." : respBody;
    }

    public CompletableFuture<String> askEd(String userId, String financialContextPrompt, boolean forceRefresh) {
        log("INFO", String.format("askEd requested: user='%s', forceRefresh=%s, promptLength=%d",
                userId, forceRefresh, financialContextPrompt != null ? financialContextPrompt.length() : 0));

        if (!isConfigured()) {
            log("WARN", String.format("askEd rejected: Mistral API key is not configured for user='%s'", userId));
            return CompletableFuture.completedFuture(
                    "Ed needs a Mistral API key to give advice! Please configure `mistral.apiKey` in `conf/application.conf` or set the `MISTRAL_API_KEY` environment variable."
            );
        }

        long now = System.currentTimeMillis();
        if (!forceRefresh && userId != null) {
            CacheEntry cached = userAdviceCache.get(userId);
            if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
                long ageSec = (now - cached.timestamp) / 1000L;
                log("INFO", String.format("askEd cache HIT for user='%s' (cached %ds ago, TTL=%ds). Returning cached advice.",
                        userId, ageSec, CACHE_TTL_MS / 1000L));
                return CompletableFuture.completedFuture(cached.text);
            }
        }

        long startTime = System.currentTimeMillis();
        String model = getModel();
        String apiKey = getApiKey();

        log("INFO", String.format("askEd sending API request: user='%s', model='%s', key=%s, endpoint='https://api.mistral.ai/v1/chat/completions'",
                userId, model, maskKey(apiKey)));

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.7);
            payload.put("max_tokens", 700);

            ArrayNode messages = payload.putArray("messages");

            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "You are Ed, financial observer in MonMon. " +
                            "Speak strictly in the style of Ernest Hemingway: lean, spare, direct, honest, and unadorned. " +
                            "Short sentences. No melodrama, no emojis, no exclamation marks, no cheap monster gimmicks or cartoon jokes. " +
                            "Face the numbers as a man faces a cold sea: honest about what is spent, calm about what remains, and disciplined about the days ahead.\n\n" +
                            "Structure:\n" +
                            "1. A brief 2-3 sentence appraisal of the financial position right now.\n" +
                            "2. Exactly 2-4 disciplined, practical actions to endure and finish the month cleanly.\n" +
                            "Use clean Markdown formatting with simple bold headings and bullet points."
            );

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", financialContextPrompt);

            String requestBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        long durationMs = System.currentTimeMillis() - startTime;
                        int status = response.statusCode();
                        String respBody = response.body();

                        if (status == 200) {
                            try {
                                JsonNode root = mapper.readTree(respBody);
                                JsonNode choices = root.path("choices");
                                JsonNode usage = root.path("usage");

                                int promptTokens = usage.path("prompt_tokens").asInt(-1);
                                int completionTokens = usage.path("completion_tokens").asInt(-1);
                                int totalTokens = usage.path("total_tokens").asInt(-1);

                                log("INFO", String.format("Mistral API SUCCESS (HTTP 200) in %dms for user='%s'. Tokens: prompt=%d, completion=%d, total=%d",
                                        durationMs, userId, promptTokens, completionTokens, totalTokens));

                                if (choices.isArray() && choices.size() > 0) {
                                    String text = choices.get(0).path("message").path("content").asText();
                                    if (userId != null && text != null && !text.isBlank()) {
                                        userAdviceCache.put(userId, new CacheEntry(text, System.currentTimeMillis()));
                                    }
                                    return text;
                                }
                                log("WARN", "Mistral API returned HTTP 200 but choices array was empty: " + respBody);
                                return "Ed looked into the crystal ball, but found no response from Mistral.";
                            } catch (Exception e) {
                                log("ERROR", String.format("Failed to parse Mistral HTTP 200 JSON for user='%s': %s. Raw: %s",
                                        userId, e.getMessage(), respBody));
                                return "Ed couldn't parse Mistral's response (" + e.getMessage() + ")";
                            }
                        } else if (status == 429) {
                            String errorDetail = extractErrorMessage(respBody);
                            log("WARN", String.format("Mistral API RATE LIMIT (HTTP 429) in %dms for user='%s'. Detail='%s'. Full response: %s",
                                    durationMs, userId, errorDetail, respBody));
                            return "Ed says: Mistral returned rate limit (HTTP 429)" +
                                    (errorDetail != null ? ": \"" + errorDetail + "\"" : "") +
                                    ". Please wait a moment before trying again, or check your Mistral tier/credits.";
                        } else if (status == 401) {
                            String errorDetail = extractErrorMessage(respBody);
                            log("ERROR", String.format("Mistral API AUTH FAILED (HTTP 401) in %dms for user='%s' using key=%s. Detail='%s'. Full response: %s",
                                    durationMs, userId, maskKey(apiKey), errorDetail, respBody));
                            return "Ed says: Mistral authentication failed (HTTP 401)" +
                                    (errorDetail != null ? ": \"" + errorDetail + "\"" : "") +
                                    ". Check that your `MISTRAL_API_KEY` is active and correct.";
                        } else {
                            String errorDetail = extractErrorMessage(respBody);
                            log("ERROR", String.format("Mistral API ERROR (HTTP %d) in %dms for user='%s'. Detail='%s'. Full response: %s",
                                    status, durationMs, userId, errorDetail, respBody));
                            return "Ed says: Mistral returned HTTP " + status +
                                    (errorDetail != null ? ": \"" + errorDetail + "\"" : ": " + respBody);
                        }
                    })
                    .exceptionally(ex -> {
                        long durationMs = System.currentTimeMillis() - startTime;
                        log("ERROR", String.format("Mistral API REQUEST FAILED in %dms for user='%s': %s",
                                durationMs, userId, ex.getMessage()));
                        return "Ed couldn't reach Mistral (" + ex.getMessage() + "). Check server logs for details.";
                    });

        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - startTime;
            log("ERROR", String.format("Error building Mistral request in %dms for user='%s': %s",
                    durationMs, userId, e.getMessage()));
            return CompletableFuture.completedFuture("Ed encountered an error preparing the request: " + e.getMessage());
        }
    }

    public CompletableFuture<String> generateNomMonPlan(String userId, float weeklyBudget, String postcode, boolean forceRefresh) {
        log("INFO", String.format("generateNomMonPlan requested: user='%s', budget=£%.2f, postcode='%s', force=%s",
                userId, weeklyBudget, postcode != null ? postcode : "", forceRefresh));

        if (!isConfigured()) {
            log("WARN", String.format("generateNomMonPlan rejected: Mistral API key is not configured for user='%s'", userId));
            return CompletableFuture.completedFuture(
                    "{\"error\":\"Mistral API key is not configured. Please set `mistral.apiKey` in `conf/application.conf` or `MISTRAL_API_KEY` in environment.\"}"
            );
        }

        long now = System.currentTimeMillis();
        if (!forceRefresh && userId != null) {
            CacheEntry cached = nomMonCache.get(userId);
            if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
                long ageSec = (now - cached.timestamp) / 1000L;
                log("INFO", String.format("NomMon cache HIT for user='%s' (cached %ds ago). Returning cached plan.",
                        userId, ageSec));
                return CompletableFuture.completedFuture(cached.text);
            }
        }

        long startTime = System.currentTimeMillis();
        String model = getModel();
        String apiKey = getApiKey();

        log("INFO", String.format("NomMon sending API request: user='%s', model='%s', budget=£%.2f",
                userId, model, weeklyBudget));

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", model);
            payload.put("temperature", 0.4);
            payload.put("max_tokens", 3000);

            ObjectNode responseFormat = payload.putObject("response_format");
            responseFormat.put("type", "json_object");

            ArrayNode messages = payload.putArray("messages");

            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "You are NomMon, an expert UK grocery planner and frugal nutritionist for MonMon.\n" +
                    "Your mission is to construct a balanced 7-day meal plan, batch cooking recipes, and an itemized Sainsbury's shopping list.\n" +
                    "STRICT REQUIREMENTS:\n" +
                    "1. Supermarket: Sainsbury's. Prioritize their entry-tier budget brand 'Stamford Street Co.' and own-brand essentials.\n" +
                    "2. Budget ceiling: The shopping basket total MUST be between 88% and 96% of the user's weekly budget (e.g. for £50.00, target £45.00 - £48.00) leaving a 4-12% safety buffer.\n" +
                    "3. Nutrition: Moderate, balanced whole foods (adequate protein 55g-70g/day, fiber, staples like oats, lentils, eggs, peanut butter, seasonal veg, wholemeal bread).\n" +
                    "4. Recipes: 2 to 4 simple batch-cooking recipes reusing common purchased ingredients.\n" +
                    "5. Output Format: Pure JSON matching this exact structure without markdown backticks:\n" +
                    "{\n" +
                    "  \"planTitle\": \"string\",\n" +
                    "  \"supermarket\": \"Sainsbury's\",\n" +
                    "  \"weeklyBudget\": float,\n" +
                    "  \"totalEstimatedCost\": float,\n" +
                    "  \"savingsBuffer\": float,\n" +
                    "  \"nutritionalHighlights\": \"string\",\n" +
                    "  \"shoppingList\": [\n" +
                    "    {\"name\": \"string\", \"category\": \"Produce\"|\"Bakery\"|\"Dairy & Eggs\"|\"Pantry & Pulses\"|\"Tins & Cans\"|\"Frozen\", \"estimatedPrice\": float, \"quantity\": number, \"unit\": \"string\"}\n" +
                    "  ],\n" +
                    "  \"days\": [\n" +
                    "    {\"dayName\": \"Monday\"...\"Sunday\", \"breakfast\": \"string\", \"lunch\": \"string\", \"dinner\": \"string\", \"snack\": \"string\", \"prepNotes\": \"string\"}\n" +
                    "  ],\n" +
                    "  \"recipes\": [\n" +
                    "    {\"title\": \"string\", \"servings\": number, \"prepTimeMinutes\": number, \"cookTimeMinutes\": number, \"ingredientsUsed\": [\"string\"], \"instructions\": [\"string\"]}\n" +
                    "  ]\n" +
                    "}"
            );

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            String prompt = String.format(java.util.Locale.US,
                    "Generate a 7-day NomMon meal plan and Sainsbury's shopping list. Weekly budget: £%.2f.%s Focus on Stamford Street Co. / Sainsbury's own brand staples. Ensure basket total is slightly below budget so there is a buffer.",
                    weeklyBudget,
                    (postcode != null && !postcode.trim().isEmpty() ? " User location/postcode: " + postcode.trim() + "." : "")
            );
            userMsg.put("content", prompt);

            String requestBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(35))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        long durationMs = System.currentTimeMillis() - startTime;
                        int status = response.statusCode();
                        String respBody = response.body();

                        if (status == 200) {
                            try {
                                JsonNode root = mapper.readTree(respBody);
                                JsonNode choices = root.path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    String text = choices.get(0).path("message").path("content").asText();
                                    if (userId != null && text != null && !text.isBlank()) {
                                        nomMonCache.put(userId, new CacheEntry(text, System.currentTimeMillis()));
                                    }
                                    return text;
                                }
                                log("WARN", "NomMon: choices array empty in response: " + respBody);
                                return "{\"error\":\"Empty response from AI\"}";
                            } catch (Exception e) {
                                log("ERROR", "NomMon JSON parse error: " + e.getMessage());
                                return "{\"error\":\"Could not parse AI response: " + e.getMessage() + "\"}";
                            }
                        } else {
                            String errorDetail = extractErrorMessage(respBody);
                            log("ERROR", String.format("NomMon API ERROR (HTTP %d) in %dms for user='%s': %s",
                                    status, durationMs, userId, errorDetail));
                            return "{\"error\":\"Mistral API returned HTTP " + status + ": " + (errorDetail != null ? errorDetail : "") + "\"}";
                        }
                    })
                    .exceptionally(ex -> {
                        long durationMs = System.currentTimeMillis() - startTime;
                        log("ERROR", String.format("NomMon request failed in %dms: %s", durationMs, ex.getMessage()));
                        return "{\"error\":\"Request failed: " + ex.getMessage() + "\"}";
                    });

        } catch (Exception e) {
            log("ERROR", "Error constructing NomMon request: " + e.getMessage());
            return CompletableFuture.completedFuture("{\"error\":\"Internal error: " + e.getMessage() + "\"}");
        }
    }
}
