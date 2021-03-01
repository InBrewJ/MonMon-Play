package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPABalanceRepository.class)
public interface BalanceRepository {

    CompletionStage<Balance> add(Balance balance);

    CompletionStage<Stream<Balance>> list(String userId);
}