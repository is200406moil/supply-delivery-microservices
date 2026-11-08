package ru.mirea.p8.order;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import ru.mirea.p8.order.Contracts.ApiException;
import ru.mirea.p8.order.Contracts.ProductDto;
import ru.mirea.p8.order.Contracts.SupplierUnavailableException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * Синхронный клиент Supplier Service (RestClient).
 * <ul>
 *   <li>timeout: connect 1 c, ответ — {@code supplier.read-timeout} (по умолчанию 2 c);</li>
 *   <li>retry: только идемпотентный GET товара (3 попытки), резервирование не повторяется;</li>
 *   <li>circuit breaker «supplier»: при постоянных отказах сразу отвечает fallback без обращения к сервису.</li>
 * </ul>
 * X-Request-ID текущего запроса передаётся в заголовке — так запрос прослеживается по логам обоих сервисов.
 */
@Component
public class SupplierClient {

    private static final Logger log = LoggerFactory.getLogger(SupplierClient.class);

    private final RestClient rest;

    public SupplierClient(RestClient.Builder builder,
                          @Value("${supplier.url}") String baseUrl,
                          @Value("${supplier.read-timeout:2s}") Duration readTimeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        this.rest = builder.baseUrl(baseUrl)
                .requestFactory(factory)
                .requestInterceptor((req, body, exec) -> {
                    String rid = MDC.get("requestId");
                    if (rid != null) {
                        req.getHeaders().set("X-Request-ID", rid);
                    }
                    return exec.execute(req, body);
                })
                .build();
    }

    @Retry(name = "supplier", fallbackMethod = "productFallback")
    @CircuitBreaker(name = "supplier")
    public ProductDto getProduct(Long productId) {
        log.info("→ Supplier Service: GET /api/suppliers/products/{}", productId);
        try {
            ProductDto p = rest.get().uri("/api/suppliers/products/{id}", productId).retrieve().body(ProductDto.class);
            log.info("← Supplier Service: «{}», цена {}, остаток {}", p.name(), p.price(), p.stock());
            return p;
        } catch (HttpClientErrorException.NotFound e) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Товар #" + productId + " не найден у поставщиков");
        }
    }

    @CircuitBreaker(name = "supplier", fallbackMethod = "reserveFallback")
    public void reserve(Long productId, int quantity) {
        log.info("→ Supplier Service: POST /api/suppliers/products/{}/reserve, количество {}", productId, quantity);
        try {
            rest.post().uri("/api/suppliers/products/{id}/reserve", productId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("quantity", quantity))
                    .retrieve().toBodilessEntity();
            log.info("← Supplier Service: товар зарезервирован");
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(HttpStatus.CONFLICT, "Поставщик отклонил резерв: недостаточно товара");
        }
    }

    // ------------------------------------------------------------------ fallback

    @SuppressWarnings("unused")
    private ProductDto productFallback(Long productId, Throwable t) {
        throw toUnavailable(t);
    }

    @SuppressWarnings("unused")
    private void reserveFallback(Long productId, int quantity, Throwable t) {
        throw toUnavailable(t);
    }

    private RuntimeException toUnavailable(Throwable t) {
        if (t instanceof ApiException api) {
            return api; // бизнес-ошибки (404, 409) не маскируем
        }
        String reason = t instanceof CallNotPermittedException
                ? "circuit breaker OPEN — запрос не отправлялся"
                : t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
        log.warn("Supplier Service недоступен, fallback: {}", reason);
        return new SupplierUnavailableException("Supplier Service недоступен (" + reason + "). Повторите позже.");
    }
}
