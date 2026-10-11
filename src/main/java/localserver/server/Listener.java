package localserver.server;

import java.net.InetSocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import localserver.config.ListenerSpec;

/** A bound, non-blocking server socket for one (host, port), plus the servers that share it. */
public final class Listener {
    private final ListenerSpec spec;
    private final ServerSocketChannel channel;
    private final InetSocketAddress address;
    private SelectionKey key;

    Listener(ListenerSpec spec, ServerSocketChannel channel, InetSocketAddress address) {
        this.spec = spec;
        this.channel = channel;
        this.address = address;
    }

    public ListenerSpec spec() {
        return spec;
    }

    public InetSocketAddress address() {
        return address;
    }

    public int port() {
        return address.getPort();
    }

    ServerSocketChannel channel() {
        return channel;
    }

    SelectionKey key() {
        return key;
    }

    void setKey(SelectionKey key) {
        this.key = key;
    }
}
