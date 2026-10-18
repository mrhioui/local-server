package localserver.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import localserver.config.ListenerSpec;
import localserver.config.LoadedConfig;
import localserver.config.ServerConfig;
import localserver.config.Settings;
import localserver.handler.ErrorPages;
import localserver.http.HttpDate;
import localserver.session.Session;
import localserver.util.Log;

/**
 * The event loop: ONE thread, ONE {@link Selector} shared by every port and every client.
 *
 * <p>Nothing here ever blocks. Each pass: wait for readiness, handle at most one operation per
 * ready key, then (a few times a second) sweep for timed-out connections. A failure on one
 * client closes that client and nothing else.
 */
public final class Server {

    /** Counters readable from other threads (tests, a future metrics endpoint). */
    public record Stats(long accepted, long requests, long timedOut, long refused, int open) { }

    private static final long SWEEP_INTERVAL_MS = 100;
    private static final long ACCEPT_PAUSE_NANOS = 100_000_000L;
    private static final long WARN_INTERVAL_NANOS = 1_000_000_000L;
    private static final int MAX_ACCEPTS_PER_EVENT = 64;
    private static final int BACKLOG = 1024;

    private final Settings settings;
    private final Selector selector;
    private final Path tempDir;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<String> bindFailures = new ArrayList<>();
    private final List<Connection> cgiWaiters = new ArrayList<>();
    private final HttpDate httpDate = new HttpDate();

    private final AtomicInteger open = new AtomicInteger();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();

    private volatile boolean stopRequested;
    private long acceptResumeAt;   // nanoTime when accepting restarts after a failure; 0 = not paused
    private long lastAcceptWarn;

    private Server(Settings settings, Selector selector, Path tempDir) {
        this.settings = settings;
        this.selector = selector;
        this.tempDir = tempDir;
    }

    // ---- start-up ------------------------------------------------------------------------

    /**
     * Binds every configured listener. A listener that cannot be bound (port in use, bad address)
     * is skipped and reported in {@link #bindFailures()}; the others keep working. Only when no
     * listener at all could be opened does this fail.
     */
    public static Server open(LoadedConfig config) throws IOException {
        Path tempDir = Files.createTempDirectory("localserver-");
        Selector selector;
        try {
            selector = Selector.open();
        } catch (IOException e) {
            deleteTree(tempDir);
            throw e;
        }
        Server server = new Server(config.settings(), selector, tempDir);
        try {
            for (ServerConfig sc : config.servers()) {
                ErrorPages.preload(sc);
            }
            for (ListenerSpec spec : config.listeners()) {
                server.bind(spec);
            }
            if (server.listeners.isEmpty()) {
                throw new IOException("no listener could be opened: " + String.join("; ", server.bindFailures));
            }
        } catch (IOException | RuntimeException e) {
            server.closeAll();
            throw e;
        }
        return server;
    }

    private void bind(ListenerSpec spec) {
        ServerSocketChannel channel = null;
        try {
            channel = ServerSocketChannel.open();
            channel.configureBlocking(false);
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            channel.bind(new InetSocketAddress(spec.host(), spec.port()), BACKLOG);
            Listener listener = new Listener(spec, channel, (InetSocketAddress) channel.getLocalAddress());
            listener.setKey(channel.register(selector, SelectionKey.OP_ACCEPT, listener));
            listeners.add(listener);
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            bindFailures.add(spec.key() + ": " + e.getMessage());
        }
    }

    public List<Listener> listeners() {
        return Collections.unmodifiableList(listeners);
    }

    public List<String> bindFailures() {
        return Collections.unmodifiableList(bindFailures);
    }

    /** Directory holding request bodies that are spooled to disk; emptied as requests finish. */
    public Path tempDir() {
        return tempDir;
    }

    public Stats stats() {
        return new Stats(accepted.get(), requests.get(), timedOut.get(), refused.get(), open.get());
    }

    // ---- the loop ----------------------------------------------------------------------------

    /** Runs until {@link #stop()} is called, then closes everything. Call from exactly one thread. */
    public void run() {
        try {
            long lastSweep = System.nanoTime();
            while (!stopRequested && selector.isOpen()) {
                try {
                    long selectTimeout = cgiWaiters.isEmpty() ? SWEEP_INTERVAL_MS : 20;
                    selector.select(selectTimeout);
                    dispatchReadyKeys();
                    long now = System.nanoTime();
                    pollCgiWaiters(now);
                    resumeAcceptingIfDue(now);
                    if (now - lastSweep >= SWEEP_INTERVAL_MS * 1_000_000L) {
                        sweep(now);
                        lastSweep = now;
                    }
                } catch (IOException | RuntimeException e) {
                    Log.warn("event loop error (continuing): " + e);
                }
            }
        } finally {
            closeAll();
        }
    }

    void registerCgiWaiter(Connection c) {
        if (!cgiWaiters.contains(c)) {
            cgiWaiters.add(c);
        }
    }

    void unregisterCgiWaiter(Connection c) {
        cgiWaiters.remove(c);
    }

    private void pollCgiWaiters(long now) {
        if (cgiWaiters.isEmpty()) {
            return;
        }
        List<Connection> copy = new ArrayList<>(cgiWaiters);
        for (Connection c : copy) {
            c.pollCgi(now);
        }
    }

    /** Asks the loop to finish. Safe to call from any thread (and from a shutdown hook). */
    public void stop() {
        stopRequested = true;
        try {
            selector.wakeup();
        } catch (RuntimeException ignored) {
            // selector already closed: nothing left to wake
        }
    }

    private void dispatchReadyKeys() {
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            it.remove();                       // Java never clears this set for you
            handle(key);
        }
    }

    /** One readiness event for one key: exactly one operation, and failures stay local. */
    private void handle(SelectionKey key) {
        try {
            if (!key.isValid()) {
                return;
            }
            int ready = key.readyOps();
            Object attachment = key.attachment();
            if (attachment instanceof Listener listener) {
                if ((ready & SelectionKey.OP_ACCEPT) != 0) {
                    accept(listener);
                }
            } else if (attachment instanceof Connection connection) {
                if ((ready & SelectionKey.OP_READ) != 0) {
                    connection.onReadable();
                } else if ((ready & SelectionKey.OP_WRITE) != 0) {
                    connection.onWritable();
                }
            }
        } catch (IOException e) {
            Log.debug("closing client after I/O error: " + e);   // resets and broken pipes are routine
            close(key);
        } catch (RuntimeException e) {
            Log.warn("unexpected error, closing client: " + e);
            close(key);
        }
    }

    // ---- accepting -----------------------------------------------------------------------------

    private void accept(Listener listener) {
        for (int i = 0; i < MAX_ACCEPTS_PER_EVENT; i++) {
            SocketChannel channel;
            try {
                channel = listener.channel().accept();     // null when nothing is waiting
            } catch (IOException e) {
                onAcceptFailure(e);
                return;
            }
            if (channel == null) {
                return;
            }
            if (open.get() >= settings.maxConnections()) {
                refused.incrementAndGet();
                closeQuietly(channel);                     // over the cap: shed load, never queue it
                continue;
            }
            try {
                channel.configureBlocking(false);
                channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                SelectionKey key = channel.register(selector, SelectionKey.OP_READ);
                key.attach(new Connection(this, key, channel, listener.spec(), settings));
                open.incrementAndGet();
                accepted.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                closeQuietly(channel);
            }
        }
    }

    /**
     * accept() failing (typically "too many open files") leaves the listener readable, so doing
     * nothing would spin the CPU. Pause accepting briefly instead and say so, at most once a second.
     */
    private void onAcceptFailure(IOException e) {
        long now = System.nanoTime();
        if (now - lastAcceptWarn > WARN_INTERVAL_NANOS) {
            Log.warn("accept failed, pausing briefly: " + e);
            lastAcceptWarn = now;
        }
        for (Listener l : listeners) {
            if (l.key().isValid()) {
                l.key().interestOps(0);
            }
        }
        acceptResumeAt = now + ACCEPT_PAUSE_NANOS;
    }

    private void resumeAcceptingIfDue(long now) {
        if (acceptResumeAt != 0 && now >= acceptResumeAt) {
            for (Listener l : listeners) {
                if (l.key().isValid()) {
                    l.key().interestOps(SelectionKey.OP_ACCEPT);
                }
            }
            acceptResumeAt = 0;
        }
    }

    // ---- timeouts and cleanup ----------------------------------------------------------------------

    private void sweep(long now) {
        Session.sweep();
        List<SelectionKey> expired = null;
        for (SelectionKey key : selector.keys()) {
            if (key.attachment() instanceof Connection c && c.timeoutReason(now, settings) != null) {
                if (expired == null) {
                    expired = new ArrayList<>();
                }
                expired.add(key);
            }
        }
        if (expired != null) {                  // close after iterating: never modify while walking keys()
            for (SelectionKey key : expired) {
                timedOut.incrementAndGet();
                Log.debug("closing timed-out connection");
                close(key);
            }
        }
    }

    /**
     * The single exit for a client, whatever went wrong. Idempotent: the attachment is cleared
     * first, so a second call finds nothing to count or release.
     */
    void close(SelectionKey key) {
        Object attachment = key.attachment();
        key.attach(null);
        key.cancel();
        closeQuietly(key.channel());
        if (attachment instanceof Connection c) {
            open.decrementAndGet();
            c.releaseResources();
        }
    }

    private void closeAll() {
        if (selector.isOpen()) {
            for (SelectionKey key : new ArrayList<>(selector.keys())) {
                Object attachment = key.attachment();
                key.attach(null);
                key.cancel();
                closeQuietly(key.channel());
                if (attachment instanceof Connection c) {
                    c.releaseResources();
                }
            }
            try {
                selector.close();
            } catch (IOException ignored) {
                // nothing sensible left to do
            }
        }
        for (Listener l : listeners) {
            closeQuietly(l.channel());
        }
        open.set(0);
        deleteTree(tempDir);
    }

    /** Best-effort recursive delete (leftover body files after an abrupt end). */
    private static void deleteTree(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // nothing sensible left to do
        }
    }

    private static void closeQuietly(java.nio.channels.Channel channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException ignored) {
            // already closed or reset
        }
    }

    // ---- small services used by Connection ---------------------------------------------------------

    long now() {
        return System.nanoTime();
    }

    String date() {
        return httpDate.now();
    }

    void requestCompleted() {
        requests.incrementAndGet();
    }
}
