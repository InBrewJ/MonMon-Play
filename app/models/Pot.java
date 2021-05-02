package models;

import javax.persistence.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(indexes = @Index(columnList = "userId"))
public class Pot {
    public enum PotType {
        MONTHLY,
        YEARLY,
        SAVING_TARGET
    }

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;

    @Column(name = "userId")
    public String userId;

    @Enumerated(EnumType.STRING)
    public PotType type;

    @Column(nullable = false)
    public Long createdAt;

    @Column(nullable = false)
    public Long lastUpdated;

    @OneToMany(
            mappedBy = "pot",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    public List<Account> accounts = new ArrayList<>();

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public PotType getType() {
        return type;
    }

    public void setType(PotType type) {
        this.type = type;
    }

    public Long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Long createdAt) {
        this.createdAt = createdAt;
    }

    public Long getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Long lastUpdated) {
        this.lastUpdated = lastUpdated;
    }

    public void addAccount(Account account) {
        accounts.add(account);
        account.setPot(this);
    }
}
