package models;

import javax.persistence.*;

@Entity
public class Balance {
    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    public Long id;
    public Long timestamp; // this makes this time series data, for graphs and junk
    public Double value;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }
}
