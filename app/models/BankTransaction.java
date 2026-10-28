package models;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import javax.persistence.*;

@Entity
@Table(name = "bank_transaction", indexes = {
        @Index(columnList = "userId"),
        @Index(columnList = "accountId"),
        @Index(columnList = "timestamp"),
        @Index(columnList = "externalId", unique = true)
})
@JsonIgnoreProperties(ignoreUnknown = true)
public class BankTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private Long id;

    @Column(name = "userId", nullable = false)
    private String userId;

    @Column(name = "accountId")
    private Long accountId;

    @Column(name = "externalId", unique = true)
    private String externalId;

    private Long timestamp;

    private String date;

    private Float amount = 0.0f;

    @Column(length = 500)
    private String description;

    private String merchantName;

    private String category;

    private String source = "MANUAL"; // MONZO, MOCK, FILE_UPLOAD, MANUAL

    private boolean pending = false;

    private Long createdAt;

    public BankTransaction() {
    }

    public BankTransaction(String userId, Long accountId, String externalId, Long timestamp, String date, Float amount, String description, String merchantName, String category, String source, boolean pending) {
        this.userId = userId;
        this.accountId = accountId;
        this.externalId = externalId;
        this.timestamp = timestamp;
        this.date = date;
        this.amount = amount != null ? amount : 0.0f;
        this.description = description;
        this.merchantName = merchantName;
        this.category = category;
        this.source = source != null ? source : "MANUAL";
        this.pending = pending;
        this.createdAt = System.currentTimeMillis();
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

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }

    public String getDate() {
        return date;
    }

    public void setDate(String date) {
        this.date = date;
    }

    public Float getAmount() {
        return amount != null ? amount : 0.0f;
    }

    public void setAmount(Float amount) {
        this.amount = amount != null ? amount : 0.0f;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getMerchantName() {
        return merchantName;
    }

    public void setMerchantName(String merchantName) {
        this.merchantName = merchantName;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public boolean isPending() {
        return pending;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }
}
