import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Discovers every class named *Test under a class directory and runs its @Test methods the way JUnit 5
 * does by default: a new instance per test, @BeforeEach first. Each test has a hard timeout so a hang
 * (e.g. a leaked permit) is reported as a failure instead of stalling the run.
 * Usage: java StandInRunner <test-classes-dir> [timeoutSeconds]
 * Output: one line per test, then "SUMMARY tests=N passed=P failed=F"; exit code 1 on any failure.
 */
public final class StandInRunner {
    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        long timeout = args.length > 1 ? Long.parseLong(args[1]) : 30;
        List<String> classNames;
        try (Stream<Path> files = Files.walk(root)) {
            classNames = files.map(p -> root.relativize(p).toString())
                    .filter(n -> n.endsWith("Test.class") && !n.contains("$"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.'))
                    .sorted().toList();
        }
        int passed = 0, failed = 0;
        ExecutorService pool = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r); t.setDaemon(true); return t; });
        for (String className : classNames) {
            Class<?> type = Class.forName(className);
            if (Modifier.isAbstract(type.getModifiers())) continue;
            List<Method> before = new ArrayList<>(), tests = new ArrayList<>();
            for (Method m : type.getDeclaredMethods()) {
                if (m.isAnnotationPresent(BeforeEach.class)) before.add(m);
                if (m.isAnnotationPresent(Test.class)) tests.add(m);
            }
            tests.sort(Comparator.comparing(Method::getName));
            for (Method test : tests) {
                String name = type.getSimpleName() + "." + test.getName();
                Future<?> run = pool.submit(() -> {
                    var ctor = type.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    Object instance = ctor.newInstance();
                    for (Method b : before) { b.setAccessible(true); b.invoke(instance); }
                    test.setAccessible(true);
                    test.invoke(instance);
                    return null;
                });
                try {
                    run.get(timeout, TimeUnit.SECONDS);
                    passed++;
                    System.out.println("PASS " + name);
                } catch (TimeoutException e) {
                    run.cancel(true);
                    failed++;
                    System.out.println("FAIL " + name + " :: timed out after " + timeout + " s (hang)");
                } catch (java.util.concurrent.ExecutionException e) {
                    Throwable cause = e.getCause() instanceof InvocationTargetException ite ? ite.getCause() : e.getCause();
                    failed++;
                    System.out.println("FAIL " + name + " :: " + String.valueOf(cause).replace('\n', ' '));
                }
            }
        }
        System.out.println("SUMMARY tests=" + (passed + failed) + " passed=" + passed + " failed=" + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
