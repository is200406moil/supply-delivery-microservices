package ru.mirea.p8.supplier;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/suppliers")
public class SupplierController {

    private static final Logger log = LoggerFactory.getLogger(SupplierController.class);

    private final SupplierRepository suppliers;
    private final ProductRepository products;
    /** Искусственная задержка ответа — для демонстрации timeout/retry в Order Service. */
    private final AtomicLong latencyMs = new AtomicLong();

    public SupplierController(SupplierRepository suppliers, ProductRepository products) {
        this.suppliers = suppliers;
        this.products = products;
    }

    public record ReserveRequest(@Min(1) int quantity) {
    }

    @GetMapping
    public List<Supplier> suppliers() {
        return suppliers.findAll();
    }

    @GetMapping("/products")
    public List<Product> products() {
        return products.findAll();
    }

    @GetMapping("/products/{id}")
    public ResponseEntity<?> product(@PathVariable Long id) throws InterruptedException {
        long delay = latencyMs.get();
        if (delay > 0) {
            log.warn("Искусственная задержка ответа {} мс", delay);
            Thread.sleep(delay);
        }
        return products.findById(id)
                .<ResponseEntity<?>>map(p -> {
                    log.info("Товар #{} «{}»: остаток {}", p.getId(), p.getName(), p.getStock());
                    return ResponseEntity.ok(p);
                })
                .orElseGet(() -> {
                    log.info("Товар #{} не найден", id);
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Товар #" + id + " не найден"));
                });
    }

    /** Резервирование товара под заказ: проверка доступности и списание остатка в своей БД. */
    @PostMapping("/products/{id}/reserve")
    @Transactional
    public ResponseEntity<?> reserve(@PathVariable Long id, @Valid @RequestBody ReserveRequest req) {
        Product p = products.findById(id).orElse(null);
        if (p == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Товар #" + id + " не найден"));
        }
        if (p.getStock() < req.quantity()) {
            log.info("Резерв отклонён: «{}» остаток {}, запрошено {}", p.getName(), p.getStock(), req.quantity());
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "Недостаточно товара «" + p.getName() + "»: остаток " + p.getStock() + ", запрошено " + req.quantity()));
        }
        p.setStock(p.getStock() - req.quantity());
        log.info("Зарезервировано {} шт. «{}», остаток {}", req.quantity(), p.getName(), p.getStock());
        return ResponseEntity.ok(Map.of("productId", p.getId(), "reserved", req.quantity(), "remaining", p.getStock()));
    }

    /** Служебный эндпоинт для демонстрации: включить/выключить задержку ответа. */
    @PostMapping("/admin/latency")
    public Map<String, Long> latency(@RequestParam long ms) {
        latencyMs.set(ms);
        log.warn("Задержка ответа установлена: {} мс", ms);
        return Map.of("latencyMs", ms);
    }
}
