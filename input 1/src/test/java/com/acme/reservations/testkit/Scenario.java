package com.acme.reservations.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test scenario method. Methods run in {@link #order()} order. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Scenario {

    /** Human readable description printed by the runner. */
    String value();

    int order() default 0;
}
