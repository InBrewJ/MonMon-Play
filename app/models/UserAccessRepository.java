package models;

import com.google.inject.ImplementedBy;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

@ImplementedBy(JPAUserAccessRepository.class)
public interface UserAccessRepository {
    CompletionStage<UserAccess> getOrCreate(String userId, String username, String email);
    CompletionStage<Optional<UserAccess>> findByUserId(String userId);
    CompletionStage<Optional<UserAccess>> findById(Long id);
    CompletionStage<List<UserAccess>> listPending();
    CompletionStage<List<UserAccess>> listApproved();
    CompletionStage<List<UserAccess>> listAll();
    CompletionStage<UserAccess> approve(Long id, String approvedBy);
    CompletionStage<UserAccess> reject(Long id, String rejectedBy);
    CompletionStage<Boolean> isUserApproved(String userId);

    UserAccess getOrCreateSync(String userId, String username, String email);
    boolean isUserApprovedSync(String userId);
}
