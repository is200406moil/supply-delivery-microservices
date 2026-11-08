package ru.mirea.p8.delivery;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;
import ru.mirea.p8.delivery.DeliveryServiceApplication.Delivery;

public interface DeliveryRepository extends ReactiveCrudRepository<Delivery, Long> {

    Mono<Delivery> findByOrderId(Long orderId);
}
