package models;

import javax.persistence.*;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Entity
@Table(name = "user_access", indexes = {
        @Index(name = "idx_user_access_user_id", columnList = "userId", unique = true)
})
public class UserAccess {

    public enum AccessStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")
            .withZone(ZoneId.of("Europe/London"));

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(nullable = false, unique = true)
    private String userId;

    private String username;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AccessStatus status = AccessStatus.PENDING;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant approvedAt;

    private String approvedBy;

    public UserAccess() {}

    public UserAccess(String userId, String username, String email, AccessStatus status) {
        this.userId = userId;
        this.username = username;
        this.email = email;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public AccessStatus getStatus() {
        return status;
    }

    public void setStatus(AccessStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Instant approvedAt) {
        this.approvedAt = approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public boolean isApproved() {
        return AccessStatus.APPROVED.equals(this.status);
    }

    public boolean isPending() {
        return AccessStatus.PENDING.equals(this.status);
    }

    public boolean isRejected() {
        return AccessStatus.REJECTED.equals(this.status);
    }

    public String getFormattedCreatedAt() {
        return createdAt != null ? FORMATTER.format(createdAt) : "—";
    }

    public String getFormattedApprovedAt() {
        return approvedAt != null ? FORMATTER.format(approvedAt) : "—";
    }
}
