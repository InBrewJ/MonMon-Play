package services;

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
                "      \"content\": \"{\\\"planTitle\\\":\\\"Sainsbury's Budget Plan\\\",\\\"weeklyBudget\\\":50.0,\\\"totalEstimatedCost\\\":47.5,\\\"savingsBuffer\\\":2.5,\\\"shoppingList\\\":[{\\\"name\\\":\\\"Stamford St Oats 1kg\\\",\\\"category\\\":\\\"Pantry\\\",\\\"estimatedPrice\\\":0.90,\\\"quantity\\\":1}],\\\"days\\\":[{\\\"dayName\\\":\\\"Monday\\\",\\\"breakfast\\\":\\\"Porridge\\\"}],\\\"recipes\\\":[{\\\"title\\\":\\\"Oat Porridge\\\"}]}\"\n" +
                "    }\n" +
                "  }]\n" +
                "}");

        when(mockHttpClient.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(mockResponse));

        MistralService service = new MistralService(config, mockHttpClient);

        // 1. Initial call
        String plan1 = service.generateNomMonPlan("userNom", 50.0f, "SW1A 1AA", false).get();
        assertThat(plan1).contains("Sainsbury's Budget Plan");
        assertThat(plan1).contains("Stamford St Oats 1kg");
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
}
