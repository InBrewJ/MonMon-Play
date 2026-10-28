package models;

import javax.persistence.*;

@Entity
@Table(name = "plaid_item", indexes = {
        @Index(columnList = "userId"),
        @Index(columnList = "itemId", unique = true)
})
public class PlaidItem {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", nullable = false)
    private String userId;

    @Column(name = "itemId", unique = true, nullable = false)
    private String itemId;

    @Column(name = "accessToken", length = 500, nullable = false)
    private String accessToken;

    private String institutionId;

    private String institutionName;

    private String cursor;

    private Long createdAt;

    private Long lastSyncedAt;

    public PlaidItem() {
    }

    public PlaidItem(String userId, String itemId, String accessToken, String institutionId, String institutionName) {
        this.userId = userId;
        this.itemId = itemId;
        this.accessToken = accessToken;
        this.institutionId = institutionId;
        this.institutionName = institutionName;
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

    public String getItemId() {
        return itemId;
    }

    public void setItemId(String itemId) {
        this.itemId = itemId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getInstitutionId() {
        return institutionId;
    }

    public void setInstitutionId(String institutionId) {
        this.institutionId = institutionId;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public void setInstitutionName(String institutionName) {
        this.institutionName = institutionName;
    }

    public String getCursor() {
        return cursor;
    }

    public void setCursor(String cursor) {
        this.cursor = cursor;
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
