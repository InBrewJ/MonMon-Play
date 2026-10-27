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
    private final Map<String, Long> userLastNomMonTimestamp = new ConcurrentHashMap<>();
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

    public static String getNomMonCacheKey(String userId, float weeklyBudget) {
        return (userId != null ? userId : "guest") + ":budget=" + Math.round(weeklyBudget * 100.0f);
    }

    public Long getNomMonTimestamp(String userId) {
        if (userId == null) return null;
        Long ts = userLastNomMonTimestamp.get(userId);
        if (ts != null) return ts;
        CacheEntry legacy = nomMonCache.get(userId);
        if (legacy != null) return legacy.timestamp;
        return nomMonCache.entrySet().stream()
                .filter(e -> e.getKey().startsWith(userId + ":"))
                .map(e -> e.getValue().timestamp)
                .max(Long::compareTo)
                .orElse(null);
    }

    public void clearNomMonCache(String userId) {
        if (userId != null) {
            nomMonCache.keySet().removeIf(k -> k.startsWith(userId + ":") || k.equals(userId));
            userLastNomMonTimestamp.remove(userId);
            log("INFO", "Cleared NomMon cache for user=" + userId);
        } else {
            nomMonCache.clear();
            userLastNomMonTimestamp.clear();
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

    public static String buildNomMonSystemPrompt() {
        return "You are NomMon, an expert UK grocery planner and adaptive nutritionist for MonMon.\n" +
                "Your mission is to construct a balanced 7-day meal plan, batch cooking recipes, and an itemized Sainsbury's shopping list that dynamically adapts to the user's weekly food budget.\n" +
                "STRICT REQUIREMENTS:\n" +
                "1. Supermarket: Sainsbury's. Adapt product selection to the user's budget tier:\n" +
                "   - FRUGAL / TIGHT BUDGETS (<= £55/week, e.g. £50.00): MUST SCREAM FRUGALITY! Prioritize Sainsbury's entry-tier budget brand 'Stamford Street Co.' and own-brand value essentials. Focus on economical, budget-stretching recipes (lentils, beans, eggs, oats, frozen veg).\n" +
                "   - MODERATE BUDGETS (£55 - £85/week, e.g. £70.00): Wholesome everyday cooking. Balance Sainsbury's own-brand staples with standard fresh chicken, beef mince, eggs, and seasonal produce.\n" +
                "   - GENEROUS BUDGETS (£85 - £140/week, e.g. £115.38): Ample spending power! Include quality fresh meats (British chicken breast fillets, lean beef mince 5%, pork loin/chops, bacon), fresh salmon fillets, fresh produce, and standard or 'Taste the Difference' items.\n" +
                "   - OPULENT / LUXURY GOURMET BUDGETS (> £140/week, e.g. £175 - £200+): MUST SCREAM OPULENCE AND GOURMET INDULGENCE! Exclusively Sainsbury's 'Taste the Difference' (TTD) range, prime butcher cuts, and luxury seafood: 30-day aged Ribeye / Sirloin / Fillet Steaks (£9-£12/pack), Scottish Salmon Fillets (£7), Raw Jumbo King Prawns (£5.50), King Scallops (£6.50), Rack of Lamb / Lamb Chops (£8), Prosciutto di Parma, Oak Smoked Salmon, 24M Parmigiano Reggiano, French Camembert, Single Estate Extra Virgin Olive Oil, Artisan Sourdough, Organic Berries, Asparagus spears, Tenderstem broccoli, Fresh herb pots. Recipes must be gourmet restaurant-standard dining.\n" +
                "2. Budget ceiling & scaling: The shopping basket total MUST strictly scale up or down to be between 88% and 96% of the user's weekly budget (e.g. for £50.00, target £44.00 - £48.00; for £115.38, target £101.50 - £110.70; for £200.00, target £176.00 - £192.00) leaving a 4-12% safety buffer.\n" +
                "   CRITICAL: You MUST NOT underspend! If the user gives a £200 budget, returning a £60-£80 basket is a FAILURE. Scale quantities of luxury cuts (e.g. 2 packs of ribeyes, 2 packs of salmon/prawns, premium cheeses, olive oil) so the basket total lands firmly inside the 88%-96% target range.\n" +
                "3. Nutrition & Dietary Variety:\n" +
                "   - On frugal budgets (£50/wk): Rely on economical protein (eggs, lentils, chickpeas, peanut butter, canned tuna, budget mince/chicken portions) and batch-cooked staples.\n" +
                "   - On opulent budgets (£200/wk): Abundant gourmet nutrition! Prime steaks, fresh seafood, rack of lamb, organic produce, fresh berries, artisan cheeses, high protein (100g-140g/day).\n" +
                "4. Recipes: 2 to 3 batch-cooking or dinner recipes utilizing the purchased ingredients. Tailor recipe style to the budget tier (frugal lentil/bean batch cooking for tight budgets; gourmet restaurant recipes with steaks, seafood, and reductions for opulent budgets). Keep instructions concise (3-4 steps).\n" +
                "5. ITEM COUNTS & BASKET VALUE: Keep the shopping list between 22 and 35 items. On higher budgets, use higher-value luxury cuts and multi-packs (e.g. £8-£12 steaks, £7 salmon, £5.50 prawns) so the basket total reaches the required 88%-96% spend target without endless tiny items.\n" +
                "6. CRITICAL ITEM QUANTITIES & PACKAGING:\n" +
                "   - 'quantity' is STRICTLY the integer number of physical retail packages/tins/loaves to buy off the supermarket shelf (usually 1, 2, or 3). NEVER set 'quantity' to grams, millilitres, or individual grains/cubes/slices!\n" +
                "   - 'packageSize' describes the weight or volume of that retail unit (e.g. '500g bag', '640g pack', '2 pack', '340g jar', '500ml bottle', '6 pack', '800g loaf', '400g tin').\n" +
                "   - 'estimatedPrice' is the retail price for ONE package unit (e.g. 0.55 for oats, 10.50 for a pack of 2 TTD Ribeye Steaks, 7.00 for Scottish Salmon, 4.60 for chicken breast fillets, 2.50 for olive oil).\n" +
                "   - Example: 2 packs of TTD Ribeye Steaks -> name: 'Taste the Difference British Beef Ribeye Steak', packageSize: '2 pack', quantity: 2, estimatedPrice: 10.50. (Total = 2 x 10.50 = £21.00).\n" +
                "   - 'totalEstimatedCost' must strictly equal the mathematical sum of (quantity * estimatedPrice) across all items in the shoppingList.\n" +
                "7. Output Format: Pure JSON matching this exact structure without markdown backticks:\n" +
                "{\n" +
                "  \"planTitle\": \"string\",\n" +
                "  \"supermarket\": \"Sainsbury's\",\n" +
                "  \"weeklyBudget\": float,\n" +
                "  \"totalEstimatedCost\": float,\n" +
                "  \"savingsBuffer\": float,\n" +
                "  \"nutritionalHighlights\": \"string\",\n" +
                "  \"shoppingList\": [\n" +
                "    {\"name\": \"string\", \"category\": \"Produce\"|\"Meat & Poultry\"|\"Dairy & Eggs\"|\"Bakery\"|\"Pantry & Pulses\"|\"Tins & Cans\"|\"Frozen\"|\"Other Staples\", \"packageSize\": \"string\", \"quantity\": number, \"estimatedPrice\": float}\n" +
                "  ],\n" +
                "  \"days\": [\n" +
                "    {\"dayName\": \"Monday\"...\"Sunday\", \"breakfast\": \"string\", \"lunch\": \"string\", \"dinner\": \"string\", \"snack\": \"string\", \"prepNotes\": \"string\"}\n" +
                "  ],\n" +
                "  \"recipes\": [\n" +
                "    {\"title\": \"string\", \"servings\": number, \"prepTimeMinutes\": number, \"cookTimeMinutes\": number, \"ingredientsUsed\": [\"string\"], \"instructions\": [\"string\"]}\n" +
                "  ]\n" +
                "}";
    }

    public static String buildNomMonUserPrompt(float weeklyBudget, String postcode) {
        float minTarget = weeklyBudget * 0.88f;
        float maxTarget = weeklyBudget * 0.96f;
        String tierDescription;

        if (weeklyBudget >= 140.0f) {
            tierDescription = String.format(java.util.Locale.US,
                    "Budget tier: PURE OPULENCE / GOURMET LUXURY (£%.2f/week).\n" +
                    "- THIS PLAN MUST SCREAM OPULENCE, LUXURY, AND GOURMET INDULGENCE!\n" +
                    "- Supermarket Range: Exclusively Sainsbury's 'Taste the Difference' (TTD) premium range, organic produce, and prime cuts.\n" +
                    "- Luxury items to include: Taste the Difference 30-day aged Ribeye / Sirloin / Fillet Steaks (£9-£12/pack), Fresh Scottish Salmon Fillets (£7.00), Raw Jumbo King Prawns (£5.50), King Scallops (£6.50), British Rack of Lamb / Lamb Chops (£8.00), Prosciutto di Parma (£3.50), Oak Smoked Scottish Salmon (£5.00), 24M Parmigiano Reggiano (£4.50), French Camembert (£3.50), Single Estate Extra Virgin Olive Oil (£7.50), Artisan Sourdough Boule (£2.50), Organic Berries (£3.00), Asparagus spears (£2.50), Tenderstem broccoli (£2.20), Fresh herb bunches (£1.00).\n" +
                    "- STRICT BASKET TARGET: Your shopping basket total MUST be between £%.2f and £%.2f (88%% to 96%% of £%.2f). DO NOT return a £60-£80 basket! To reach £%.2f+, select these luxury items and buy multiple packs of prime steaks (e.g. qty 2), salmon (qty 2), king prawns (qty 2), luxury cheeses, and gourmet pantry staples.\n" +
                    "- Recipes: Opulent restaurant-quality recipes (e.g. Pan-seared Ribeye with truffle garlic herb butter & asparagus, Seared Scallop & King Prawn tagliatelle, Herb-crusted Rack of Lamb).",
                    weeklyBudget, minTarget, maxTarget, weeklyBudget, minTarget);
        } else if (weeklyBudget >= 85.0f) {
            tierDescription = String.format(java.util.Locale.US,
                    "Budget tier: GENEROUS / HIGH (£%.2f/week).\n" +
                    "- The user has ample food budget! Include quality fresh meat, poultry, and fish (e.g. chicken breast fillets, lean minced beef, salmon fillets, pork loin/chops), fresh produce, and standard Sainsbury's and Taste the Difference selections.\n" +
                    "- STRICT BASKET TARGET: Scale the basket items and quantities so the total estimated cost is between £%.2f and £%.2f (88%% to 96%% of £%.2f, leaving a 4-12%% buffer). DO NOT underspend with a cheap basket.\n" +
                    "- Feature fresh meats and wholesome, rich recipes across the 7-day schedule.",
                    weeklyBudget, minTarget, maxTarget, weeklyBudget);
        } else if (weeklyBudget >= 55.0f) {
            tierDescription = String.format(java.util.Locale.US,
                    "Budget tier: MODERATE / BALANCED (£%.2f/week).\n" +
                    "- Incorporate a balanced selection of fresh chicken, beef mince, seasonal produce, and Sainsbury's own-brand staples.\n" +
                    "- STRICT BASKET TARGET: Basket total MUST be between £%.2f and £%.2f.",
                    weeklyBudget, minTarget, maxTarget);
        } else {
            tierDescription = String.format(java.util.Locale.US,
                    "Budget tier: FRUGAL / BUDGET STAPLES (£%.2f/week).\n" +
                    "- THIS PLAN MUST SCREAM FRUGALITY AND COST-EFFICIENCY!\n" +
                    "- Prioritize Sainsbury's entry-tier brand 'Stamford Street Co.' and own-brand value staples.\n" +
                    "- Protein should come from economical sources: eggs, lentils, chickpeas, peanut butter, canned tuna, budget poultry portions.\n" +
                    "- Hearty batch cooking that stretches ingredients (dal, chili, pasta bake, oats).\n" +
                    "- STRICT BASKET TARGET: Basket total MUST be between £%.2f and £%.2f with a safety buffer.",
                    weeklyBudget, minTarget, maxTarget);
        }

        return String.format(java.util.Locale.US,
                "Generate a 7-day NomMon meal plan and Sainsbury's shopping list.\n%s%s\nKeep shopping list to 22-35 items and meal descriptions concise so full JSON completes cleanly.\nRemember: 'quantity' is STRICTLY integer count of retail physical packs (e.g. 1 pack of steaks, 1 bag of oats), NEVER grams or ml.",
                tierDescription,
                (postcode != null && !postcode.trim().isEmpty() ? "\nUser location/postcode: " + postcode.trim() + "." : "")
        );
    }

    public static JsonNode attemptRepairTruncatedJson(String json, ObjectMapper mapper) {
        if (json == null || json.isBlank()) return null;
        String trimmed = json.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();

        // 1. Try parsing directly first
        try {
            return mapper.readTree(trimmed);
        } catch (Exception ignored) {
        }

        // 2. Scan backwards to find a clean truncation point (e.g. after a complete property value)
        int maxLookback = Math.min(trimmed.length(), 4000);
        int startPos = trimmed.length();
        int endPos = Math.max(0, trimmed.length() - maxLookback);

        for (int i = startPos; i >= endPos; i--) {
            String candidate = trimmed.substring(0, i).trim();
            if (candidate.endsWith(",")) {
                candidate = candidate.substring(0, candidate.length() - 1).trim();
            }

            java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
            boolean inString = false;
            boolean escaped = false;
            boolean validCandidate = true;

            for (int j = 0; j < candidate.length(); j++) {
                char ch = candidate.charAt(j);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (ch == '\\') {
                    escaped = true;
                    continue;
                }
                if (ch == '"') {
                    inString = !inString;
                    continue;
                }
                if (!inString) {
                    if (ch == '{') stack.push('}');
                    else if (ch == '[') stack.push(']');
                    else if (ch == '}' || ch == ']') {
                        if (stack.isEmpty() || stack.peek() != ch) {
                            validCandidate = false;
                            break;
                        }
                        stack.pop();
                    }
                }
            }

            if (!validCandidate || inString) {
                continue;
            }

            // Skip if candidate ends with dangling colon or incomplete key
            if (candidate.endsWith(":") || candidate.matches(".*\"[a-zA-Z0-9_]+\"\\s*:\\s*$")) {
                continue;
            }

            StringBuilder closer = new StringBuilder();
            while (!stack.isEmpty()) {
                closer.append(stack.pop());
            }

            String repaired = candidate + closer.toString();
            try {
                JsonNode node = mapper.readTree(repaired);
                if (node != null && node.isObject() && (node.has("shoppingList") || node.has("planTitle"))) {
                    return node;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
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

        String cacheKey = getNomMonCacheKey(userId, weeklyBudget);
        long now = System.currentTimeMillis();
        if (!forceRefresh && userId != null) {
            CacheEntry cached = nomMonCache.get(cacheKey);
            if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
                long ageSec = (now - cached.timestamp) / 1000L;
                log("INFO", String.format("NomMon cache HIT for user='%s' key='%s' (cached %ds ago). Returning cached plan.",
                        userId, cacheKey, ageSec));
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
            payload.put("max_tokens", 6000);

            ObjectNode responseFormat = payload.putObject("response_format");
            responseFormat.put("type", "json_object");

            ArrayNode messages = payload.putArray("messages");

            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content", buildNomMonSystemPrompt());

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", buildNomMonUserPrompt(weeklyBudget, postcode));

            String requestBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(60))
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
                                    JsonNode choice0 = choices.get(0);
                                    String text = choice0.path("message").path("content").asText();
                                    String finishReason = choice0.path("finish_reason").asText();
                                    if ("length".equalsIgnoreCase(finishReason)) {
                                        log("WARN", "NomMon response was truncated by Mistral max_tokens (finish_reason=length)");
                                    }

                                    JsonNode parsedPlan = attemptRepairTruncatedJson(text, mapper);
                                    if (parsedPlan != null) {
                                        String cleanJson = mapper.writeValueAsString(parsedPlan);
                                        if (userId != null) {
                                            nomMonCache.put(cacheKey, new CacheEntry(cleanJson, System.currentTimeMillis()));
                                            userLastNomMonTimestamp.put(userId, System.currentTimeMillis());
                                        }
                                        return cleanJson;
                                    } else if (text != null && !text.isBlank()) {
                                        log("ERROR", "NomMon response could not be parsed or repaired. Raw: " + text);
                                        return "{\"error\":\"Meal plan generation was cut short. Please click Regenerate to retry.\",\"rawPlan\":" + mapper.writeValueAsString(text) + "}";
                                    }
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
