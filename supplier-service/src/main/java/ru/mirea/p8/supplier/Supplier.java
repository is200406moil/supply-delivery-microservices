package ru.mirea.p8.supplier;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "suppliers")
public class Supplier {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String inn;

    protected Supplier() {
    }

    public Supplier(String name, String inn) {
        this.name = name;
        this.inn = inn;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getInn() { return inn; }
}
