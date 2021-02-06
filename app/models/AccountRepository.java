package models;

import com.google.inject.ImplementedBy;

import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAAccountRepository.class)
public interface AccountRepository {

    CompletionStage<Account> add(Account account);

    CompletionStage<Stream<Account>> list();

    CompletionStage<Stream<Account>> listComplete();

    CompletionStage<Account> archive(int accountId);

    CompletionStage<Account> findById(int accountId);

    CompletionStage<Account> update(int accountId, Account account);
}
