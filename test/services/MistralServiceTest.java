package services;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import models.Account;
import org.junit.Test;
import viewModels.SpendingPlan;
import viewModels.Spog;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class MistralServiceTest {

    @Test
    public void testIsConfiguredFalseWhenKeyMissing() throws ExecutionException, InterruptedException {
        Config emptyConfig = ConfigFactory.parseString("mistral { apiKey = \"\" }");
        MistralService service = new MistralService(emptyConfig);

        assertThat(service.isConfigured()).isFalse();

        CompletableFuture<String> future = service.askEd("user1", "prompt", false);
        String response = future.get();
        assertThat(response).contains("Ed needs a Mistral API key");
    }

    @Test
    public void testBuildFinancialSnapshotPromptContainsAllMetrics() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        Account acc = new Account();
        acc.setName("Current Account");
        acc.setType(Account.AccountType.DEBIT);
        models.Balance bal = new models.Balance();
        bal.setValue(1500f);
        bal.setTimestamp(System.currentTimeMillis() / 1000L);
        acc.balances = new java.util.ArrayList<>(List.of(bal));

        SpendingPlan plan = new SpendingPlan("Gruelling", 200f, 0f, 0f, 174.61f, 374.61f, 150.0, 5);

        Spog spog = new Spog(
                374.61f, // surplus / max allowance
                1,       // nextPayday
                0,       // percentIncomeAsSavings
                3000f,   // incomingTotal
                2625.39f,// outgoingTotal
                500f,    // rentCost
                0f,      // remainderRentCost
                150f,    // billsCost
                0f,      // remainderBillsCost
                1000f,   // completedOutgoingsSum
                200f,    // pendingOutgoingsSum
                List.of(acc),
                List.of(acc),
                today,
                plan,
                null
        );

        String prompt = MistralService.buildFinancialSnapshotPrompt(spog);

        assertThat(prompt).contains("Income & Allowances:");
        assertThat(prompt).contains("Monthly Income / Takehome: £3000.0");
        assertThat(prompt).contains("Max Monthly Spending Allowance: £374.61");
        assertThat(prompt).contains("Credit & Debt:");
        assertThat(prompt).contains("Total Credit Debt Owed:");
        assertThat(prompt).contains("Savings & Assets:");
        assertThat(prompt).contains("Accounts:");
        assertThat(prompt).contains("Current Account (DEBIT): £1500.0");
        assertThat(prompt).contains("Spending Plan (Gruelling):");
        assertThat(prompt).contains("Planned Food: £200.0");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testAskEdWithMockHttpClientAndCaching() throws ExecutionException, InterruptedException {
        Config config = ConfigFactory.parseString("mistral { apiKey = \"test-key-123\", model = \"mistral-small-latest\" }");
        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);

        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\n" +
                "  \"choices\": [\n" +
                "    {\n" +
                "      \"message\": {\n" +
                "        \"content\": \"**Ed says:** You are in solid shape! Watch out for weekend dining.\"\n" +
                "      }\n" +
                "    }\n" +
                "  ]\n" +
                "}");

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        MistralService service = new MistralService(config, mockHttpClient);
        assertThat(service.isConfigured()).isTrue();
        assertThat(service.getModel()).isEqualTo("mistral-small-latest");

        // First call - should call HTTP
        String advice1 = service.askEd("userA", "snapshot prompt", false).get();
        assertThat(advice1).contains("You are in solid shape!");
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // Second call with forceRefresh=false - should return cached without calling HTTP again
        String advice2 = service.askEd("userA", "snapshot prompt", false).get();
        assertThat(advice2).isEqualTo(advice1);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // Third call with forceRefresh=true - should call HTTP again
        String advice3 = service.askEd("userA", "snapshot prompt", true).get();
        assertThat(advice3).isEqualTo(advice1);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        assertThat(service.getLastSpokeTimestamp("userA")).isNotNull();
        assertThat(service.getLastSpokeTimestamp("userA")).isGreaterThan(0L);
        assertThat(service.getLastSpokeTimestamp("unknownUser")).isNull();
    }

    @Test
    public void testGenerateNomMonPlanRejectedWhenKeyMissing() throws ExecutionException, InterruptedException {
        Config emptyConfig = ConfigFactory.parseString("mistral { apiKey = \"\" }");
        MistralService service = new MistralService(emptyConfig);

        String json = service.generateNomMonPlan("user1", 50.0f, "SW1A 1AA", false).get();
        assertThat(json).contains("Mistral API key is not configured");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testGenerateNomMonPlanWithMockHttpClientAndCaching() throws ExecutionException, InterruptedException, IOException {
        Config config = ConfigFactory.parseString(
                "mistral {\n" +
                        "  apiKey = \"test-key-nommon-123\"\n" +
                        "  model = \"mistral-small-latest\"\n" +
                        "}"
        );

        HttpClient mockHttpClient = mock(HttpClient.class);
        HttpResponse<String> mockResponse = mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn("{\n" +
                "  \"choices\": [{\n" +
                "    \"message\": {\n" +
                "      \"role\": \"assistant\",\n" +
                "      \"content\": \"{\\\"planTitle\\\":\\\"Sainsbury's Budget Plan\\\",\\\"weeklyBudget\\\":50.0,\\\"totalEstimatedCost\\\":47.5,\\\"savingsBuffer\\\":2.5,\\\"shoppingList\\\":[{\\\"name\\\":\\\"Stamford St Oats\\\",\\\"category\\\":\\\"Pantry\\\",\\\"packageSize\\\":\\\"500g bag\\\",\\\"estimatedPrice\\\":0.55,\\\"quantity\\\":1}],\\\"days\\\":[{\\\"dayName\\\":\\\"Monday\\\",\\\"breakfast\\\":\\\"Porridge\\\"}],\\\"recipes\\\":[{\\\"title\\\":\\\"Oat Porridge\\\"}]}\"\n" +
                "    }\n" +
                "  }]\n" +
                "}");

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        MistralService service = new MistralService(config, mockHttpClient);

        // 1. Initial call
        String plan1 = service.generateNomMonPlan("userNom", 50.0f, "SW1A 1AA", false).get();
        assertThat(plan1).contains("Sainsbury's Budget Plan");
        assertThat(plan1).contains("Stamford St Oats");
        assertThat(plan1).contains("500g bag");
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // 2. Cached call
        String plan2 = service.generateNomMonPlan("userNom", 50.0f, "SW1A 1AA", false).get();
        assertThat(plan2).isEqualTo(plan1);
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // 3. Forced call
        String plan3 = service.generateNomMonPlan("userNom", 50.0f, "SW1A 1AA", true).get();
        assertThat(plan3).isEqualTo(plan1);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        assertThat(service.getNomMonTimestamp("userNom")).isNotNull();
        assertThat(service.getNomMonTimestamp("userNom")).isGreaterThan(0L);
    }

    @Test
    public void testNomMonPromptsAdaptToBudgetTiers() {
        String systemPrompt = MistralService.buildNomMonSystemPrompt();
        assertThat(systemPrompt).contains("Meat & Poultry");
        assertThat(systemPrompt).contains("GENEROUS / HIGHER BUDGETS");
        assertThat(systemPrompt).contains("FRUGAL / TIGHT BUDGETS");
        assertThat(systemPrompt).contains("Stamford Street Co.");

        // High budget £115.38: should clearly instruct Mistral that meat/seafood is affordable
        String highPrompt = MistralService.buildNomMonUserPrompt(115.38f, "SW1A 1AA");
        assertThat(highPrompt).contains("GENEROUS / HIGH");
        assertThat(highPrompt).contains("£115.38/week");
        assertThat(highPrompt).contains("quality fresh meat");
        assertThat(highPrompt).contains("DO NOT return a bare-bones budget basket");
        assertThat(highPrompt).contains("SW1A 1AA");

        // Frugal budget £50.00: should guide towards economical staples & batch cooking
        String budgetPrompt = MistralService.buildNomMonUserPrompt(50.0f, null);
        assertThat(budgetPrompt).contains("FRUGAL / BUDGET");
        assertThat(budgetPrompt).contains("£50.00/week");
        assertThat(budgetPrompt).contains("Stamford Street Co.");
        assertThat(budgetPrompt).contains("economical protein");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testNomMonCacheDifferentiatesBudgets() throws ExecutionException, InterruptedException {
        Config config = ConfigFactory.parseString("mistral { apiKey = \"test-key-nommon-cache\", model = \"mistral-small-latest\" }");
        HttpClient mockHttpClient = mock(HttpClient.class);

        HttpResponse<String> resp115 = mock(HttpResponse.class);
        when(resp115.statusCode()).thenReturn(200);
        when(resp115.body()).thenReturn("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"planTitle\\\":\\\"Generous Plan\\\",\\\"weeklyBudget\\\":115.38}\"}}]}");

        HttpResponse<String> resp50 = mock(HttpResponse.class);
        when(resp50.statusCode()).thenReturn(200);
        when(resp50.body()).thenReturn("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"planTitle\\\":\\\"Budget Plan\\\",\\\"weeklyBudget\\\":50.0}\"}}]}");

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(resp115))
                .thenReturn(CompletableFuture.completedFuture(resp50));

        MistralService service = new MistralService(config, mockHttpClient);

        // 1. Fetch for £115.38
        String plan115 = service.generateNomMonPlan("user1", 115.38f, null, false).get();
        assertThat(plan115).contains("Generous Plan");
        verify(mockHttpClient, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // 2. Fetch for £50.00: must NOT hit £115.38 cache; should trigger HTTP request #2
        String plan50 = service.generateNomMonPlan("user1", 50.0f, null, false).get();
        assertThat(plan50).contains("Budget Plan");
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // 3. Repeat £50.00: should hit cache (still 2 calls)
        String plan50Cached = service.generateNomMonPlan("user1", 50.0f, null, false).get();
        assertThat(plan50Cached).isEqualTo(plan50);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        // 4. Repeat £115.38: should hit cache (still 2 calls)
        String plan115Cached = service.generateNomMonPlan("user1", 115.38f, null, false).get();
        assertThat(plan115Cached).isEqualTo(plan115);
        verify(mockHttpClient, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    public void testAttemptRepairTruncatedJson() {
        ObjectMapper mapper = new ObjectMapper();

        // 1. Truncated mid-day on Saturday (exact user error scenario)
        String truncated = "{\n" +
                "  \"planTitle\": \"Generous 7-Day Plan\",\n" +
                "  \"supermarket\": \"Sainsbury's\",\n" +
                "  \"weeklyBudget\": 115.38,\n" +
                "  \"totalEstimatedCost\": 108.23,\n" +
                "  \"shoppingList\": [\n" +
                "    {\"name\": \"Sainsbury's British Chicken Breast Fillets\", \"quantity\": 2, \"estimatedPrice\": 4.60}\n" +
                "  ],\n" +
                "  \"days\": [\n" +
                "    {\n" +
                "      \"dayName\": \"Monday\",\n" +
                "      \"breakfast\": \"Oats\",\n" +
                "      \"dinner\": \"Chicken\"\n" +
                "    },\n" +
                "    {\n" +
                "      \"dayName\": \"Saturday\",\n" +
                "      \"breakfast\": \"Eggs\",\n" +
                "      \"dinner\": \"Beef chilli\",\n" +
                "      \"snack\":";

        com.fasterxml.jackson.databind.JsonNode repaired = MistralService.attemptRepairTruncatedJson(truncated, mapper);
        assertThat(repaired).isNotNull();
        assertThat(repaired.has("planTitle")).isTrue();
        assertThat(repaired.path("planTitle").asText()).isEqualTo("Generous 7-Day Plan");
        assertThat(repaired.has("shoppingList")).isTrue();
        assertThat(repaired.path("shoppingList").size()).isEqualTo(1);
        assertThat(repaired.has("days")).isTrue();
        assertThat(repaired.path("days").size()).isGreaterThanOrEqualTo(1);

        // 2. Valid complete JSON
        String valid = "{\"planTitle\":\"Complete Plan\",\"shoppingList\":[]}";
        com.fasterxml.jackson.databind.JsonNode validNode = MistralService.attemptRepairTruncatedJson(valid, mapper);
        assertThat(validNode).isNotNull();
        assertThat(validNode.path("planTitle").asText()).isEqualTo("Complete Plan");

        // 3. Null or garbage
        assertThat(MistralService.attemptRepairTruncatedJson(null, mapper)).isNull();
        assertThat(MistralService.attemptRepairTruncatedJson("", mapper)).isNull();
    }
}
