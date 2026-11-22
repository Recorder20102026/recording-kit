package com.acme.reservations.testkit;

import com.acme.reservations.api.HttpResponse;
import com.acme.reservations.domain.CreateReservationRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

/** Fires many requests at exactly the same moment (one platform thread per request). */
public final class LoadRunner {

    /** A request plus the pod the load balancer sends it to. */
    public record Submission(CreateReservationRequest request, String node,
                             Function<CreateReservationRequest, HttpResponse> target) {
    }

    public record Outcome(CreateReservationRequest request, HttpResponse response, long latencyNanos) {
    }

    public record Run(List<Outcome> outcomes, long wallNanos) {
    }

    private LoadRunner() {
    }

    public static Run fireTogether(List<Submission> submissions) {
        int n = submissions.size();
        Outcome[] outcomes = new Outcome[n];
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            final int index = i;
            Submission s = submissions.get(i);
            threads.add(Thread.ofPlatform().name("client-" + i + "->" + s.node()).start(() -> {
                ready.countDown();
                await(go);
                long t0 = System.nanoTime();
                HttpResponse response = s.target().apply(s.request());
                outcomes[index] = new Outcome(s.request(), response, System.nanoTime() - t0);
            }));
        }
        await(ready);
        long start = System.nanoTime();
        go.countDown();
        for (Thread t : threads) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        return new Run(List.of(outcomes), System.nanoTime() - start);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
