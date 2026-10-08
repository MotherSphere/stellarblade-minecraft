package org.stellarbridge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Builds the avatar for the wanted component table on a daemon worker, off the render thread. One build runs at a time;
 * wanting another table supersedes the previous one, whose result is discarded, and take hands a result over only with the
 * table it was built from, while that table is still wanted, so a model never meets another generation's table. The render
 * thread calls want, wanted and take.
 */
final class AvatarRebuild<T> implements AutoCloseable {
    interface Builder<T> { T build(AvatarStream.Table table) throws Exception; }
    /** A finished build: its table and result, or the failure building it. */
    record Built<T>(AvatarStream.Table table, T result, Exception failure) { }

    private final Builder<T> builder;
    private final Consumer<T> discard; // Frees a result nobody takes.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EVE avatar build");
        thread.setDaemon(true);
        return thread;
    });
    private AvatarStream.Table wanted, started; // Guarded by this.
    private Built<T> ready;
    private boolean closed;

    AvatarRebuild(Builder<T> builder, Consumer<T> discard) {
        this.builder = builder;
        this.discard = discard;
    }

    /** The table to build, or null for none (the drawn model fits the newest table); a newly wanted generation queues a build. */
    synchronized void want(AvatarStream.Table table) {
        if (table == null ? wanted == null : wanted != null && wanted.generation() == table.generation()) return;
        wanted = table;
        if (ready != null) { drop(ready); ready = null; }
        if (table != null && !closed) worker.execute(this::build);
    }
    synchronized AvatarStream.Table wanted() { return wanted; }

    /** The wanted table's finished build, once; null while it runs or when nothing is wanted. */
    synchronized Built<T> take() {
        Built<T> built = ready;
        ready = null;
        return built;
    }

    private void build() {
        AvatarStream.Table table;
        synchronized (this) {
            table = wanted;
            if (table == null || table == started || closed) return; // Superseded before it began, or already built.
            started = table;
        }
        T result = null;
        Exception failure = null;
        try {
            result = builder.build(table);
        } catch (Exception problem) {
            failure = problem;
        }
        synchronized (this) {
            if (table == wanted && !closed) {
                ready = new Built<>(table, result, failure);
                return;
            }
        }
        if (result != null) discard.accept(result);
    }

    private void drop(Built<T> built) {
        if (built.result() != null) discard.accept(built.result());
    }

    @Override public synchronized void close() {
        closed = true;
        wanted = null;
        if (ready != null) { drop(ready); ready = null; }
        worker.shutdown(); // A running build discards its result.
    }
}
