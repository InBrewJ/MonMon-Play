package services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.typesafe.config.Config;
import models.Account;
import viewModels.SpendingPlan;
import viewModels.Spog;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Singleton
public class MistralService {
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
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L; // 15 minutes TTL

    @Inject
    public MistralService(Config config) {
        this(config, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build());
    }

    public MistralService(Config config, HttpClient httpClient) {
        this.config = config;
        this.httpClient = httpClient;
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
        } else {
            userAdviceCache.clear();
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

        if (spogVm.getAllAccounts() != null && !spogVm.getAllAccounts().isEmpty()) {
            sb.append("Accounts:\n");
            for (Account a : spogVm.getAllAccounts()) {
                if (a != null) {
                    sb.append("  • ").append(a.getName() != null ? a.getName() : "Account")
                            .append(" (").append(a.getType() != null ? a.getType() : "DEBIT").append("): £")
                            .append(a.getLatestBalanceValue()).append("\n");
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

        sb.append("Please analyze these finances as Ed. Give me an assessment of how I'm doing and clear, practical actions to improve or manage my money for the rest of this month.");
        return sb.toString();
    }

    public CompletableFuture<String> askEd(String userId, String financialContextPrompt, boolean forceRefresh) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(
                    "Ed needs a Mistral API key to give advice! Please configure `mistral.apiKey` in `conf/application.conf` or set the `MISTRAL_API_KEY` environment variable."
            );
        }

        long now = System.currentTimeMillis();
        if (!forceRefresh && userId != null) {
            CacheEntry cached = userAdviceCache.get(userId);
            if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
                return CompletableFuture.completedFuture(cached.text);
            }
        }

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("model", getModel());
            payload.put("temperature", 0.7);
            payload.put("max_tokens", 700);

            ArrayNode messages = payload.putArray("messages");

            ObjectNode systemMsg = messages.addObject();
            systemMsg.put("role", "system");
            systemMsg.put("content",
                    "You are Ed, the sharp-witted, helpful, and monstrously practical personal finance monster mascot of MonMon. " +
                            "Analyze the user's financial dashboard snapshot (Single Pane of Glass - SPOG). Provide:\n" +
                            "1. A quick 2-3 sentence overview of their financial health right now (are they on track, ahead, or burning cash?).\n" +
                            "2. Exactly 2-4 punchy, high-impact, practical bullet points on what they can do to improve or survive until payday.\n" +
                            "Tone: direct, encouraging, clever, with a tiny hint of monstrous wit. No generic fluff. Use Markdown formatting with bolding and bullet points."
            );

            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content", financialContextPrompt);

            String requestBody = mapper.writeValueAsString(payload);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                    .header("Authorization", "Bearer " + getApiKey())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(25))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenApply(response -> {
                        int status = response.statusCode();
                        String respBody = response.body();

                        if (status == 200) {
                            try {
                                JsonNode root = mapper.readTree(respBody);
                                JsonNode choices = root.path("choices");
                                if (choices.isArray() && choices.size() > 0) {
                                    String text = choices.get(0).path("message").path("content").asText();
                                    if (userId != null && text != null && !text.isBlank()) {
                                        userAdviceCache.put(userId, new CacheEntry(text, System.currentTimeMillis()));
                                    }
                                    return text;
                                }
                                return "Ed looked into the crystal ball, but found no response from Mistral.";
                            } catch (Exception e) {
                                return "Ed couldn't parse Mistral's response (" + e.getMessage() + ")";
                            }
                        } else if (status == 401) {
                            return "Ed says: The Mistral API key provided appears to be invalid or unauthorized. Please verify your `MISTRAL_API_KEY`.";
                        } else if (status == 429) {
                            return "Ed says: Mistral's rate limit was reached! Give it a minute, then click 'Ask Ed' to try again.";
                        }
                        return "Ed says: Mistral returned HTTP " + status + ": " + respBody;
                    })
                    .exceptionally(ex -> "Ed couldn't reach Mistral: " + ex.getMessage());

        } catch (Exception e) {
            return CompletableFuture.completedFuture("Ed encountered an error preparing the request: " + e.getMessage());
        }
    }
}
