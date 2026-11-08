package ru.mirea.p8.order;

import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.mirea.p8.order.Contracts.ApiError;
import ru.mirea.p8.order.Contracts.ApiException;
import ru.mirea.p8.order.Contracts.CreateOrderRequest;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService service;

    public OrderController(OrderService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CustomerOrder> create(@Valid @RequestBody CreateOrderRequest req) {
        CustomerOrder o = service.create(req);
        return ResponseEntity.created(URI.create("/api/orders/" + o.getId())).body(o);
    }

    @GetMapping
    public List<CustomerOrder> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public CustomerOrder findById(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping("/{id}/resend-event")
    public CustomerOrder resend(@PathVariable Long id) {
        return service.resendEvent(id);
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> api(ApiException e) {
        return error(e.status(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        return error(HttpStatus.BAD_REQUEST, e.getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).collect(Collectors.joining("; ")));
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(new ApiError(status.value(), status.getReasonPhrase(), message, MDC.get("requestId"), Instant.now()));
    }
}
