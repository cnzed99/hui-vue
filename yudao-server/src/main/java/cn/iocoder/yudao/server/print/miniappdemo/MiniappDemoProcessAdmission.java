package cn.iocoder.yudao.server.print.miniappdemo;

import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One shared, non-queuing admission gate for every untrusted miniapp child JVM.
 * A lease is released exactly once and must be held for the child's full lifetime.
 */
@Component
public final class MiniappDemoProcessAdmission {
    private final Semaphore slot = new Semaphore(1, true);

    public Lease tryAcquire() {
        return slot.tryAcquire() ? new Lease(slot) : null;
    }

    public static final class Lease implements AutoCloseable {
        private final Semaphore slot;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(Semaphore slot) {
            this.slot = slot;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) slot.release();
        }
    }
}
