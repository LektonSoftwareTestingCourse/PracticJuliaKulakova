package com.processing.service;

import com.processing.Properties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit-тесты HTTP-клиента комиссии эквайринга ({@link MerchantAcquirerClient})
 * на базе {@link MockRestServiceServer}.
 */
class MerchantAcquirerClientTest {

    private static final String FEE_URL = Properties.MERCHANT_ACQUIRER_URL + "/api/simulator/merchant/fee";
    private static final Instant TRANSMISSION_TIME = Instant.parse("2026-10-26T12:00:00Z");

    private MockRestServiceServer mockServer;
    private MerchantAcquirerClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new MerchantAcquirerClient(Properties.defaults(), builder.build());
    }

    @AfterEach
    void tearDown() {
        mockServer.reset();
    }

    @Test
    void shouldReturnAcquiringFeeWhenMerchantAcquirerResponds() {
        mockServer.expect(requestTo(FEE_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"acquirerFee\": 12.34}", MediaType.APPLICATION_JSON));

        BigDecimal fee = client.fetchAcquiringFee(
                TRANSMISSION_TIME, "000001", "4000001234567890", "TERM0001", new BigDecimal("1000"));

        assertThat(fee).isEqualByComparingTo("12.34");
        mockServer.verify();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerRespondsWithError() {
        mockServer.expect(requestTo(FEE_URL))
                .andRespond(withServerError());

        BigDecimal fee = client.fetchAcquiringFee(
                TRANSMISSION_TIME, "000001", "4000001234567890", "TERM0001", new BigDecimal("1000"));

        assertThat(fee).isNull();
        mockServer.verify();
    }

    @Test
    void shouldReturnNullWhenMerchantAcquirerRespondsWithEmptyBody() {
        mockServer.expect(requestTo(FEE_URL))
                .andRespond(withSuccess());

        BigDecimal fee = client.fetchAcquiringFee(
                TRANSMISSION_TIME, "000001", "4000001234567890", "TERM0001", new BigDecimal("1000"));

        assertThat(fee).isNull();
        mockServer.verify();
    }
}
