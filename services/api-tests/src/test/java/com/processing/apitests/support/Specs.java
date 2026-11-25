package com.processing.apitests.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.filter.log.LogDetail;
import io.restassured.filter.log.RequestLoggingFilter;
import io.restassured.filter.log.ResponseLoggingFilter;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Общие {@link RequestSpecification}: базовый адрес и заголовки задаются один раз,
 * чтобы не дублировать их в каждом тесте.
 */
public final class Specs {

    private Specs() {
    }

    public static RequestSpecification gateway() {
        return jsonSpec(TestConfig.gatewayUrl());
    }

    public static RequestSpecification cardManagement() {
        return jsonSpec(TestConfig.cardManagementUrl());
    }

    public static RequestSpecification switchService() {
        return jsonSpec(TestConfig.switchUrl());
    }

    public static RequestSpecification authorization() {
        return jsonSpec(TestConfig.authorizationUrl());
    }

    public static RequestSpecification logger() {
        return jsonSpec(TestConfig.loggerUrl());
    }

    public static RequestSpecification terminalSimulator() {
        return jsonSpec(TestConfig.terminalSimulatorUrl());
    }

    public static RequestSpecification merchantSimulator() {
        return jsonSpec(TestConfig.merchantSimulatorUrl());
    }

    private static RequestSpecification jsonSpec(String baseUri) {
        return new RequestSpecBuilder()
                .setBaseUri(baseUri)
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .addFilter(new RequestLoggingFilter(LogDetail.ALL))
                .addFilter(new ResponseLoggingFilter(LogDetail.ALL))
                .build();
    }
}
