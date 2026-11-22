package com.acme.reservations.testkit;

import com.acme.reservations.suites.*;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Zero-dependency test runner.
 * <pre>
 *   ./scripts/test.sh                 # all suites
 *   ./scripts/test.sh overlap burst   # selected suites
 * </pre>
 * Each suite class also has a {@code main} so it can be run / debugged from the IntelliJ gutter.
 */
public final class TestRunner {

    private static final Map<String, Class<?>> SUITES = new LinkedHashMap<>();

    static {
        SUITES.put("overlap", OverlappingIntervalTest.class);
        SUITES.put("burst", BurstContentionTest.class);
        SUITES.put("multinode", MultiNodeConsistencyTest.class);
        SUITES.put("partial", PartialFailureTest.class);
        SUITES.put("timeout", LockTimeoutRetryTest.class);
        SUITES.put("regression", RegressionTest.class);
    }

    private record Failure(String suite, String scenario, String message) {
    }

    private TestRunner() {
    }

    public static void main(String[] args) {
        List<Class<?>> selected = new ArrayList<>();
        if (args.length == 0 || Arrays.asList(args).contains("all")) {
            selected.addAll(SUITES.values());
        } else {
            for (String arg : args) {
                Class<?> suite = SUITES.get(arg.toLowerCase());
                if (suite == null) {
                    System.err.println("unknown suite '" + arg + "'. Known: " + SUITES.keySet() + " or 'all'");
                    System.exit(2);
                }
                selected.add(suite);
            }
        }
        System.exit(run(selected));
    }

    /** Entry point for the per-suite {@code main} methods. */
    public static void runAndExit(Class<?>... suites) {
        System.exit(run(List.of(suites)));
    }

    public static int run(List<Class<?>> suites) {
        Fixtures.config();
        String line = "═".repeat(100);
        System.out.println(line);
        System.out.println(" reservation-service test run  " + LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).substring(0, 19)
                + "  suites=" + suites.stream().map(Class::getSimpleName).toList());
        System.out.println(" config/reservation.properties: " + configSummary());
        System.out.printf(" constraints: lock wait <= %dms, retries <= %d, burst = %dx, nodes = %d, amplification <= %.2fx%n",
                Constraints.LOCK_WAIT_BUDGET.toMillis(), Constraints.MAX_RETRIES, Constraints.BURST_FACTOR,
                Constraints.NODES, Constraints.MAX_RETRY_AMPLIFICATION);
        System.out.println(line);

        long t0 = System.nanoTime();
        int passed = 0;
        List<Failure> failures = new ArrayList<>();
        for (Class<?> suite : suites) {
            System.out.println();
            System.out.println("▶ " + suite.getSimpleName());
            Object instance = newInstance(suite);
            List<Method> scenarios = Arrays.stream(suite.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Scenario.class))
                    .sorted(Comparator.comparingInt((Method m) -> m.getAnnotation(Scenario.class).order())
                            .thenComparing(Method::getName))
                    .toList();
            for (Method m : scenarios) {
                String description = m.getAnnotation(Scenario.class).value();
                System.out.println("   ● " + m.getName() + " - " + description);
                long s0 = System.nanoTime();
                try {
                    m.setAccessible(true);
                    m.invoke(instance);
                    passed++;
                    System.out.printf("   [PASS] %s (%.0f ms)%n%n", m.getName(), (System.nanoTime() - s0) / 1e6);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    String message = cause instanceof AssertionError ? cause.getMessage()
                            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    failures.add(new Failure(suite.getSimpleName(), m.getName(), message));
                    System.out.printf("   [FAIL] %s (%.0f ms)%n%n", m.getName(), (System.nanoTime() - s0) / 1e6);
                    if (!(cause instanceof AssertionError)) {
                        cause.printStackTrace(System.out);
                    }
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        System.out.println(line);
        System.out.printf(" RESULT: %d passed, %d failed   (%.1f s)%n", passed, failures.size(), (System.nanoTime() - t0) / 1e9);
        for (Failure f : failures) {
            System.out.println("   ✘ " + f.suite() + "." + f.scenario() + ": " + f.message());
        }
        System.out.println(line);
        return failures.isEmpty() ? 0 : 1;
    }

    /** Raw view of the keys that matter for this investigation (independent of ReservationConfig's shape). */
    private static String configSummary() {
        Properties p = new Properties();
        Path path = Path.of(System.getProperty("reservation.config", "config/reservation.properties"));
        try (InputStream in = Files.newInputStream(path)) {
            p.load(in);
        } catch (IOException e) {
            return "(not found: " + path.toAbsolutePath() + ")";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("db.lock.wait.timeout.ms", "cluster.nodes", "reservation.local.lock.enabled",
                "retry.max.retries", "retry.backoff.ms", "retry.backoff.base.ms", "retry.backoff.max.ms",
                "slot.bucket.minutes")) {
            if (p.getProperty(key) != null) {
                sb.append(key).append('=').append(p.getProperty(key).trim()).append("  ");
            }
        }
        return sb.toString().trim();
    }

    private static Object newInstance(Class<?> suite) {
        try {
            var ctor = suite.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot instantiate " + suite, e);
        }
    }
}
