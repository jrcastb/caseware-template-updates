package org.junit.jupiter.api;
import java.lang.annotation.*;
/** Minimal stand-in for JUnit 5's {@code @BeforeEach}. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface BeforeEach {}
