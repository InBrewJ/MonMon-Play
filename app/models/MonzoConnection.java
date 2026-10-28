package models;

import javax.persistence.*;

@Entity
@Table(name = "monzo_connection", indexes = {
        @Index(columnList = "userId", unique = true)
})
public class MonzoConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", unique = true, nullable = false)
    private String userId;

    @Column(name = "accessToken", length = 1000, nullable = false)
    private String accessToken;

    @Column(length = 1000)
    private String refreshToken;

    private String monzoUserId;

    private Long expiresAt;

    private Long createdAt;

    private Long lastSyncedAt;

    public MonzoConnection() {
    }

    public MonzoConnection(String userId, String accessToken, String refreshToken, String monzoUserId, Long expiresAt) {
        this.userId = userId;
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.monzoUserId = monzoUserId;
        this.expiresAt = expiresAt;
        this.createdAt = System.currentTimeMillis();
        this.lastSyncedAt = System.currentTimeMillis();
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

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public String getMonzoUserId() {
        return monzoUserId;
    }

    public void setMonzoUserId(String monzoUserId) {
        this.monzoUserId = monzoUserId;
    }

    public Long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Long lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
