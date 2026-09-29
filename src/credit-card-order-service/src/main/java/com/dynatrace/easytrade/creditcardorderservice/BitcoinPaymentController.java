package com.dynatrace.easytrade.creditcardorderservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.StandardResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;

/**
 * Bitcoin payment API.
 *
 * <p>The endpoints are deliberately thin: {@code POST /v1/bitcoin-payments}
 * accepts and persists a payment as PENDING and returns immediately (202) — it
 * never blocks on the external gateway. {@code GET /v1/bitcoin-payments/{id}}
 * lets clients poll for progress, mirroring how the rest of the service already
 * exposes status via polling.
 */
@RestController
@RequestMapping(value = "/v1/bitcoin-payments",
        produces = { "application/json", "application/xml" })
@CrossOrigin
@ApiResponses(value = {
        @ApiResponse(responseCode = "202", description = "Payment accepted and pending", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "400", description = "Bad request", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "404", description = "Payment not found", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
        @ApiResponse(responseCode = "503", description = "Feature disabled", content =
                @Content(schema = @Schema(implementation = StandardResponse.class))),
})
public class BitcoinPaymentController {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentController.class);

    public static final String FEATURE_DISABLED = "Bitcoin payments are not currently enabled.";
    public static final String PAYMENT_ACCEPTED = "Bitcoin payment accepted and is pending.";
    public static final String PAYMENT_FOUND = "Bitcoin payment found.";
    public static final String PAYMENT_NOT_FOUND = "No bitcoin payment exists with the given id.";
    public static final String INVALID_REQUEST = "Invalid bitcoin payment request.";

    private final BitcoinPaymentService paymentService;

    public BitcoinPaymentController(BitcoinPaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping(value = "", consumes = { "application/json", "application/xml" })
    @Operation(summary = "Initiate a bitcoin payment for a credit card order")
    public ResponseEntity<StandardResponse> initiate(@RequestBody BitcoinPaymentRequest request) {
        if (!paymentService.isEnabled()) {
            logger.info("Rejected bitcoin payment: feature flag disabled");
            return build(HttpStatus.SERVICE_UNAVAILABLE, FEATURE_DISABLED, null);
        }
        final BitcoinPayment payment;
        try {
            payment = paymentService.initiate(request);
        } catch (NullPointerException | IllegalArgumentException e) {
            return build(HttpStatus.BAD_REQUEST, INVALID_REQUEST, null, e.getMessage());
        }
        return build(HttpStatus.ACCEPTED, PAYMENT_ACCEPTED, payment);
    }

    @GetMapping("/{paymentId}")
    @Operation(summary = "Get the status of a bitcoin payment")
    public ResponseEntity<StandardResponse> get(@PathVariable String paymentId) {
        return paymentService.get(paymentId)
                .map(p -> build(HttpStatus.OK, PAYMENT_FOUND, p))
                .orElseGet(() -> build(HttpStatus.NOT_FOUND, PAYMENT_NOT_FOUND, null));
    }

    private ResponseEntity<StandardResponse> build(HttpStatus status, String message, Object results) {
        return build(status, message, results, null);
    }

    private ResponseEntity<StandardResponse> build(HttpStatus status, String message, Object results, Object error) {
        return ResponseEntity.status(status)
                .body(new StandardResponse(status.value(), message, results, null, error));
    }
}
