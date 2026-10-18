package org.junit.jupiter.api;
import java.lang.annotation.*;
/** Minimal stand-in for JUnit 5's {@code @Test}; same package and name, so the test sources are unchanged. */
@Retention(RetentionPolicy.RUNTIME) @Target(ElementType.METHOD) public @interface Test {}
