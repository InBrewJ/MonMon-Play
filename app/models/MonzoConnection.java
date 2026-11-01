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

    @Column(name = "accessToken", length = 1000)
    private String accessToken;

    @Column(length = 1000)
    private String refreshToken;

    private String monzoUserId;

    private Long expiresAt;

    private Long createdAt;

    private Long lastSyncedAt;

    private String webhookDebitId;

    private String webhookFlexId;

    @Column(length = 500)
    private String webhookUrl;

    private Long lastReceivedAt;

    @Column(length = 500)
    private String clientId;

    @Column(length = 1000)
    private String clientSecret;

    @Column(length = 500)
    private String redirectUri;

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

    public String getWebhookDebitId() {
        return webhookDebitId;
    }

    public void setWebhookDebitId(String webhookDebitId) {
        this.webhookDebitId = webhookDebitId;
    }

    public String getWebhookFlexId() {
        return webhookFlexId;
    }

    public void setWebhookFlexId(String webhookFlexId) {
        this.webhookFlexId = webhookFlexId;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public void setWebhookUrl(String webhookUrl) {
        this.webhookUrl = webhookUrl;
    }

    public Long getLastReceivedAt() {
        return lastReceivedAt;
    }

    public void setLastReceivedAt(Long lastReceivedAt) {
        this.lastReceivedAt = lastReceivedAt;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public boolean hasAccessToken() {
        return accessToken != null && !accessToken.trim().isEmpty();
    }

    public boolean hasRefreshToken() {
        return refreshToken != null && !refreshToken.trim().isEmpty();
    }

    public boolean isLongTermOAuth() {
        return hasRefreshToken();
    }

    /**
     * Returns true if expiresAt is set and the token is either already expired
     * or within 5 minutes (300,000 ms) of expiring.
     */
    public boolean isTokenExpired() {
        if (expiresAt == null || expiresAt <= 0L) {
            return false;
        }
        long expiresAtMs = expiresAt < 100_000_000_000L ? expiresAt * 1000L : expiresAt;
        return System.currentTimeMillis() >= (expiresAtMs - 300_000L);
    }

    public boolean hasActiveWebhooks() {
        return (webhookDebitId != null && !webhookDebitId.isEmpty()) ||
               (webhookFlexId != null && !webhookFlexId.isEmpty());
    }
}
