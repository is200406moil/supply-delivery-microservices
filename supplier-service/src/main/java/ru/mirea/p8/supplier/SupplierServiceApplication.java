package ru.mirea.p8.supplier;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.math.BigDecimal;

/**
 * Supplier Service — поставщики и товары (АС Поставщиков из BootcampLabs).
 * Собственная БД suppliers_db, доступ к ней есть только у этого сервиса.
 */
@SpringBootApplication
public class SupplierServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplierServiceApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(SupplierRepository suppliers, ProductRepository products) {
        return args -> {
            if (suppliers.count() > 0) {
                return;
            }
            Supplier bakery = suppliers.save(new Supplier("Пекарня «Хлебный дом»", "7701234567"));
            Supplier farm = suppliers.save(new Supplier("Молочная ферма «Луговое»", "5009876543"));
            products.save(new Product(bakery.getId(), "Батон нарезной", new BigDecimal("59.90"), 50));
            products.save(new Product(bakery.getId(), "Круассан с маслом", new BigDecimal("89.00"), 20));
            products.save(new Product(farm.getId(), "Молоко 3,2% 1 л", new BigDecimal("99.90"), 30));
            products.save(new Product(farm.getId(), "Сыр «Российский» 200 г", new BigDecimal("299.00"), 0));
        };
    }

}
