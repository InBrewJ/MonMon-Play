package models;

import com.google.inject.ImplementedBy;

import java.time.LocalDate;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * This interface provides a non-blocking API for possibly blocking operations.
 */
@ImplementedBy(JPAPlanRepository.class)
public interface PlanRepository {
    CompletionStage<Plan> add(Plan plan);

    CompletionStage<Plan> archive(int planId);

    CompletionStage<Stream<Plan>> list(String userId);

    CompletionStage<Plan> createOrReplace(String userId, Plan.PlanType type, Plan plan);

    CompletionStage<Stream<Plan>> listComplete(String userId);
}
