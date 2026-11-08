package ru.mirea.p8.order;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;

/** DTO, события и исключения Order Service. */
public final class Contracts {

    private Contracts() {
    }

    public record CreateOrderRequest(@NotBlank String customerName, @NotBlank String address,
                                     @NotNull Long productId, @Min(1) int quantity) {
    }

    /** Ответ Supplier Service о товаре (копия контракта, а не общий класс — сервисы независимы). */
    public record ProductDto(Long id, Long supplierId, String name, BigDecimal price, int stock) {
    }

    /** Событие для брокера: только данные, нужные Delivery Service для создания доставки. */
    public record OrderCreatedEvent(String eventId, Long orderId, String customerName, String address,
                                    String productName, int quantity, Instant createdAt) {
    }

    /** Событие от Delivery Service. */
    public record DeliveryStatusChangedEvent(String eventId, Long orderId, Long deliveryId, String status,
                                             String courier, Instant at) {
    }

    public record ApiError(int status, String error, String message, String requestId, Instant timestamp) {
    }

    public static class ApiException extends RuntimeException {
        private final HttpStatus status;

        public ApiException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    /** Supplier Service недоступен / circuit breaker открыт — 503 без бесконечного ожидания. */
    public static class SupplierUnavailableException extends ApiException {
        public SupplierUnavailableException(String message) {
            super(HttpStatus.SERVICE_UNAVAILABLE, message);
        }
    }
}
