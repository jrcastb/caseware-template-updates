package org.junit.jupiter.api;

import java.util.Objects;
import java.util.function.Supplier;
import org.junit.jupiter.api.function.Executable;

/**
 * Minimal stand-in for the subset of JUnit 5 {@code Assertions} the tests use. It deliberately has FEWER
 * overloads than real JUnit, so anything that compiles here also resolves in real JUnit 5.10.
 */
public final class Assertions {
    private Assertions() {}

    public static void fail(String message) { throw new AssertionError(message); }

    public static void assertTrue(boolean condition) { assertTrue(condition, "expected true"); }
    public static void assertTrue(boolean condition, String message) { if (!condition) fail(message); }
    public static void assertTrue(boolean condition, Supplier<String> message) { if (!condition) fail(message.get()); }
    public static void assertFalse(boolean condition) { assertFalse(condition, "expected false"); }
    public static void assertFalse(boolean condition, String message) { if (condition) fail(message); }

    public static void assertEquals(int expected, int actual) { assertEquals(expected, actual, (String) null); }
    public static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) fail(format(expected, actual, message));
    }
    public static void assertEquals(long expected, long actual) { assertEquals(expected, actual, (String) null); }
    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) fail(format(expected, actual, message));
    }
    public static void assertEquals(Object expected, Object actual) { assertEquals(expected, actual, (String) null); }
    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) fail(format(expected, actual, message));
    }

    public static void assertNotNull(Object actual) { if (actual == null) fail("expected not null"); }

    public static <T> T assertInstanceOf(Class<T> expectedType, Object actual) {
        if (!expectedType.isInstance(actual)) {
            fail("expected instance of " + expectedType.getSimpleName() + " but was " + actual);
        }
        return expectedType.cast(actual);
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable) {
        try {
            executable.execute();
        } catch (Throwable actual) {
            if (expectedType.isInstance(actual)) return expectedType.cast(actual);
            throw new AssertionError("expected " + expectedType.getSimpleName() + " but threw " + actual, actual);
        }
        throw new AssertionError("expected " + expectedType.getSimpleName() + " to be thrown, but nothing was");
    }

    public static <T extends Throwable> T assertThrows(Class<T> expectedType, Executable executable, String message) {
        try {
            return assertThrows(expectedType, executable);
        } catch (AssertionError e) {
            throw new AssertionError(message + " ==> " + e.getMessage(), e);
        }
    }

    private static String format(Object expected, Object actual, String message) {
        return (message == null ? "" : message + " ==> ") + "expected: <" + expected + "> but was: <" + actual + ">";
    }
}
