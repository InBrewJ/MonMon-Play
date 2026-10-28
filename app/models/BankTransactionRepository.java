package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

@ImplementedBy(JPABankTransactionRepository.class)
public interface BankTransactionRepository {

    CompletionStage<BankTransaction> save(BankTransaction transaction);

    CompletionStage<Integer> saveAll(List<BankTransaction> transactions);

    CompletionStage<Stream<BankTransaction>> listRecent(String userId, int limit);

    CompletionStage<Stream<BankTransaction>> findByAccount(Long accountId, Long startSec, Long endSec);

    CompletionStage<Double> getAccountSpentSum(Long accountId, Long startSec, Long endSec);

    CompletionStage<Map<Long, Double>> getAccountsSpentSumMap(String userId, Long startSec, Long endSec);

    CompletionStage<Integer> deleteForAccount(Long accountId);

    CompletionStage<Integer> deleteByTimeRange(Long accountId, Long startSec, Long endSec);

    CompletionStage<List<BankTransaction>> findGeotaggedTransactions(String userId, int limit);

    CompletionStage<List<viewModels.CategorySpend>> getCategorySpendingHistogram(String userId);
}
