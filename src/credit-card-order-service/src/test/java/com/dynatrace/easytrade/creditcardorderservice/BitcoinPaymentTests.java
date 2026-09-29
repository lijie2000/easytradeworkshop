package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatus;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
public class BitcoinPaymentTests {

    @Mock
    OpenFeatureAPI openFeatureAPI;
    @Mock
    Client featureClient;
    @Mock
    BitcoinGatewayClient gatewayClient;

    private final BitcoinPaymentRequest REQUEST =
            new BitcoinPaymentRequest(13, "order-123", new BigDecimal("49.99"), "USD");

    private BitcoinPaymentService serviceWithFlag(boolean enabled) {
        lenient().when(openFeatureAPI.getClient()).thenReturn(featureClient);
        lenient().when(featureClient.getBooleanValue(anyString(), Mockito.anyBoolean())).thenReturn(enabled);
        return new BitcoinPaymentService(new BitcoinPaymentRepository(), gatewayClient, openFeatureAPI);
    }

    // ---- Request validation ----

    @Test
    void rejectsNonPositiveAmount() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitcoinPaymentRequest(1, "o", new BigDecimal("0"), "USD"));
    }

    @Test
    void rejectsBadCurrency() {
        assertThrows(IllegalArgumentException.class,
                () -> new BitcoinPaymentRequest(1, "o", new BigDecimal("1"), "DOLLARS"));
    }

    @Test
    void rejectsNullFields() {
        assertThrows(NullPointerException.class,
                () -> new BitcoinPaymentRequest(null, "o", new BigDecimal("1"), "USD"));
    }

    // ---- Service behavior ----

    @Test
    void initiatePersistsPendingPayment() {
        BitcoinPaymentService service = serviceWithFlag(true);
        BitcoinPayment payment = service.initiate(REQUEST);

        assertNotNull(payment.paymentId());
        assertEquals(BitcoinPaymentStatus.PENDING, payment.status());
        assertEquals("order-123", payment.orderId());
        assertNull(payment.paymentAddress(), "gateway not called on request path");
        // initiate must not touch the gateway (kept off the request path)
        Mockito.verifyNoInteractions(gatewayClient);
    }

    @Test
    void initiateIsIdempotentPerOrder() {
        BitcoinPaymentService service = serviceWithFlag(true);
        BitcoinPayment first = service.initiate(REQUEST);
        BitcoinPayment second = service.initiate(REQUEST);
        assertEquals(first.paymentId(), second.paymentId(), "same order should not create a duplicate");
    }

    @Test
    void flagGatesTheFeature() {
        assertTrue(serviceWithFlag(true).isEnabled());
        assertFalse(serviceWithFlag(false).isEnabled());
    }

    @Test
    void advanceMovesPendingToAwaitingConfirmationOnQuote() {
        BitcoinPaymentService service = serviceWithFlag(true);
        BitcoinPayment payment = service.initiate(REQUEST);

        Mockito.when(gatewayClient.createPayment(anyString(), Mockito.any(), anyString()))
                .thenReturn(new BitcoinGatewayClient.GatewayQuote("bc1qaddr", new BigDecimal("0.0012")));

        service.advance(payment);

        BitcoinPayment updated = service.get(payment.paymentId()).orElseThrow();
        assertEquals(BitcoinPaymentStatus.AWAITING_CONFIRMATION, updated.status());
        assertEquals("bc1qaddr", updated.paymentAddress());
    }

    @Test
    void advanceStaysPendingWhenGatewayUnavailable() {
        BitcoinPaymentService service = serviceWithFlag(true);
        BitcoinPayment payment = service.initiate(REQUEST);

        // gateway returns null (down / circuit open) -> no state change, no crash
        Mockito.when(gatewayClient.createPayment(anyString(), Mockito.any(), anyString())).thenReturn(null);
        service.advance(payment);

        assertEquals(BitcoinPaymentStatus.PENDING,
                service.get(payment.paymentId()).orElseThrow().status());
    }

    @Test
    void advanceConfirmsAfterGatewayConfirms() {
        BitcoinPaymentService service = serviceWithFlag(true);
        BitcoinPayment payment = service.initiate(REQUEST);
        Mockito.when(gatewayClient.createPayment(anyString(), Mockito.any(), anyString()))
                .thenReturn(new BitcoinGatewayClient.GatewayQuote("bc1qaddr", new BigDecimal("0.0012")));
        service.advance(payment); // -> AWAITING_CONFIRMATION

        BitcoinPayment awaiting = service.get(payment.paymentId()).orElseThrow();
        Mockito.when(gatewayClient.getPaymentState(anyString()))
                .thenReturn(BitcoinGatewayClient.BitcoinPaymentGatewayState.CONFIRMED);
        service.advance(awaiting);

        assertEquals(BitcoinPaymentStatus.CONFIRMED,
                service.get(payment.paymentId()).orElseThrow().status());
    }

    // ---- Controller behavior ----

    @Test
    void controllerReturns503WhenDisabled() {
        BitcoinPaymentController controller = new BitcoinPaymentController(serviceWithFlag(false));
        ResponseEntity<?> response = controller.initiate(REQUEST);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), response.getStatusCode().value());
    }

    @Test
    void controllerAccepts202WhenEnabled() {
        BitcoinPaymentController controller = new BitcoinPaymentController(serviceWithFlag(true));
        ResponseEntity<?> response = controller.initiate(REQUEST);
        assertEquals(HttpStatus.ACCEPTED.value(), response.getStatusCode().value());
    }

    @Test
    void controllerReturns404ForUnknownPayment() {
        BitcoinPaymentController controller = new BitcoinPaymentController(serviceWithFlag(true));
        ResponseEntity<?> response = controller.get("does-not-exist");
        assertEquals(HttpStatus.NOT_FOUND.value(), response.getStatusCode().value());
    }
}
