/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.network.p2p.network;

import bisq.network.p2p.NodeAddress;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.app.Capabilities;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.proto.network.NetworkProtoResolver;
import bisq.common.util.Utilities;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;

import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.SimpleObjectProperty;

import java.net.ServerSocket;
import java.net.Socket;

import java.io.IOException;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public abstract class NetworkNode implements MessageListener, ConnectionListener {
    protected final int servicePort;
    private final NetworkProtoResolver networkProtoResolver;
    protected final ListeningExecutorService executorService;

    private final CopyOnWriteArraySet<MessageListener> messageListeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<ConnectionListener> connectionListeners = new CopyOnWriteArraySet<>();
    protected final CopyOnWriteArraySet<SetupListener> setupListeners = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<InboundConnection> inBoundConnections = new CopyOnWriteArraySet<>();
    private final CopyOnWriteArraySet<OutboundConnection> outBoundConnections = new CopyOnWriteArraySet<>();
    protected final ObjectProperty<NodeAddress> nodeAddressProperty = new SimpleObjectProperty<>();
    private Server server;
    private volatile boolean shutDownInProgress;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected NetworkNode(int servicePort, NetworkProtoResolver networkProtoResolver) {
        this.servicePort = servicePort;
        this.networkProtoResolver = networkProtoResolver;

        executorService = Utilities.getListeningExecutorService("NetworkNode-" + servicePort,
                15, 30, 60);
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public abstract void start(@Nullable SetupListener setupListener);

    protected void startServer(ServerSocket serverSocket) {
        server = new Server(serverSocket,
                this,
                this,
                networkProtoResolver);
        executorService.submit(server);
    }

    public SettableFuture<Connection> sendMessage(Connection connection, NetworkEnvelope networkEnvelope) {
        SettableFuture<Connection> resultFuture = SettableFuture.create();

        ListenableFuture<Connection> future = executorService.submit(() -> {
            String id = connection.getPeersNodeAddressOptional().isPresent() ?
                    connection.getPeersNodeAddressOptional().get().getFullAddress() :
                    connection.getUid();
            Thread.currentThread().setName("NetworkNode:SendMessage-to-" + id);
            connection.sendMessage(networkEnvelope);
            return connection;
        });

        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(Connection connection) {
                UserThread.execute(() -> resultFuture.set(connection));
            }

            public void onFailure(@NotNull Throwable throwable) {
                UserThread.execute(() -> resultFuture.setException(throwable));
            }
        }, MoreExecutors.directExecutor());

        return resultFuture;
    }

    public SettableFuture<Connection> sendMessage(NodeAddress peer, NetworkEnvelope networkEnvelope) {
        log.debug("Send {} to {}. Message details: {}",
                networkEnvelope.getClass().getSimpleName(),
                peer,
                Utilities.toTruncatedString(networkEnvelope));

        checkNotNull(peer, "peerAddress must not be null");
        checkArgument(!peer.equals(getNodeAddress()), "Peer address is our own address");

        Optional<Connection> existingConnection = findExistingConnection(peer);
        if (existingConnection.isPresent()) {
            return sendMessage(existingConnection.get(), networkEnvelope);
        } else {
            return sendMessageOnNewConnection(peer, networkEnvelope);
        }
    }

    public Set<Connection> getAllConnections() {
        // Can contain inbound and outbound connections with the same peer node address,
        // as connection hashcode is using uid and port info
        Set<Connection> set = new HashSet<>(inBoundConnections);
        set.addAll(outBoundConnections);
        return set;
    }

    public Set<Connection> getConfirmedConnections() {
        // Can contain inbound and outbound connections with the same peer node address,
        // as connection hashcode is using uid and port info
        return getAllConnections().stream()
                .filter(Connection::hasPeersNodeAddress)
                .collect(Collectors.toSet());
    }

    public Set<NodeAddress> getNodeAddressesOfConfirmedConnections() {
        // Does not contain inbound and outbound connection with the same peer node address
        return getConfirmedConnections().stream()
                .filter(e -> e.getPeersNodeAddressOptional().isPresent())
                .map(e -> e.getPeersNodeAddressOptional().get())
                .collect(Collectors.toSet());
    }

    public void shutDown(Runnable shutDownCompleteHandler) {
        if (!shutDownInProgress) {
            shutDownInProgress = true;
            if (server != null) {
                server.shutDown();
                server = null;
            }

            Set<Connection> allConnections = getAllConnections();
            int numConnections = allConnections.size();

            if (numConnections == 0) {
                log.info("Shutdown immediately because no connections are open.");
                if (shutDownCompleteHandler != null) {
                    shutDownCompleteHandler.run();
                }
                return;
            }

            log.info("Shutdown {} connections", numConnections);

            AtomicInteger shutdownCompleted = new AtomicInteger();
            Timer timeoutHandler = UserThread.runAfter(() -> {
                if (shutDownCompleteHandler != null) {
                    log.info("Shutdown completed due timeout");
                    shutDownCompleteHandler.run();
                }
            }, 3);

            allConnections.forEach(c -> c.shutDown(CloseConnectionReason.APP_SHUT_DOWN,
                    () -> {
                        log.info("Shutdown of node {} completed", c.getPeersNodeAddressOptional());
                        if (shutdownCompleted.incrementAndGet() == numConnections) {
                            log.info("Shutdown completed with all connections closed");
                            timeoutHandler.stop();
                            if (shutDownCompleteHandler != null) {
                                shutDownCompleteHandler.run();
                            }
                        }
                    }));
        }
    }

    public Optional<Capabilities> findPeersCapabilities(NodeAddress nodeAddress) {
        return getConfirmedConnections().stream()
                .filter(c -> c.getPeersNodeAddressProperty().get() != null)
                .filter(c -> c.getPeersNodeAddressProperty().get().equals(nodeAddress))
                .map(Connection::getCapabilities)
                .findAny();
    }

    @Nullable
    public NodeAddress getNodeAddress() {
        return nodeAddressProperty.get();
    }

    public ReadOnlyObjectProperty<NodeAddress> nodeAddressProperty() {
        return nodeAddressProperty;
    }

    @Nullable
    public abstract Socks5Proxy getSocksProxy();

    protected abstract Socket createSocket(NodeAddress peersNodeAddress) throws IOException;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ConnectionListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onConnection(Connection connection) {
        if (!connection.isStopped()) {
            if (connection instanceof OutboundConnection) {
                outBoundConnections.add((OutboundConnection) connection);
                printOutBoundConnections();
            } else {
                inBoundConnections.add((InboundConnection) connection);
                printInboundConnections();
            }
            connectionListeners.forEach(e -> e.onConnection(connection));
        }
    }

    @Override
    public void onDisconnect(CloseConnectionReason closeConnectionReason,
                             Connection connection) {
        if (connection instanceof OutboundConnection) {
            outBoundConnections.remove(connection);
            printOutBoundConnections();
        } else {
            inBoundConnections.remove(connection);
            printInboundConnections();
        }

        connectionListeners.forEach(e -> e.onDisconnect(closeConnectionReason, connection));
    }

    @Override
    public void onError(Throwable throwable) {
        log.error(throwable.getMessage());
        connectionListeners.forEach(e -> e.onError(throwable));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // MessageListener implementation
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMessage(NetworkEnvelope networkEnvelope, Connection connection) {
        messageListeners.forEach(e -> e.onMessage(networkEnvelope, connection));
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Listeners
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void addSetupListener(SetupListener setupListener) {
        setupListeners.add(setupListener);
    }

    public void addConnectionListener(ConnectionListener connectionListener) {
        boolean isNewEntry = connectionListeners.add(connectionListener);
        if (!isNewEntry)
            log.warn("Try to add a connectionListener which was already added.\n\tconnectionListener={}\n\tconnectionListeners={}"
                    , connectionListener, connectionListeners);
    }

    public void removeConnectionListener(ConnectionListener connectionListener) {
        boolean contained = connectionListeners.remove(connectionListener);
        if (!contained)
            log.debug("Try to remove a connectionListener which was never added.\n\t" +
                    "That might happen because of async behaviour of CopyOnWriteArraySet");
    }

    public void addMessageListener(MessageListener messageListener) {
        boolean isNewEntry = messageListeners.add(messageListener);
        if (!isNewEntry)
            log.warn("Try to add a messageListener which was already added.");
    }

    public void removeMessageListener(MessageListener messageListener) {
        boolean contained = messageListeners.remove(messageListener);
        if (!contained)
            log.debug("Try to remove a messageListener which was never added.\n\t" +
                    "That might happen because of async behaviour of CopyOnWriteArraySet");
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private SettableFuture<Connection> sendMessageOnNewConnection(NodeAddress peersNodeAddress,
                                                                  NetworkEnvelope networkEnvelope) {
        SettableFuture<Connection> resultFuture = SettableFuture.create();

        ListenableFuture<Connection> future = executorService.submit(() -> {
            Thread.currentThread().setName("NetworkNode:SendMessage-to-" + peersNodeAddress.getFullAddress());
            try {
                long startTs = System.currentTimeMillis();
                Socket socket = createSocket(peersNodeAddress);
                log.info("Socket creation to peersNodeAddress {} took {} ms", peersNodeAddress.getFullAddress(),
                        System.currentTimeMillis() - startTs);

                // Tor needs sometimes quite long to create a connection. To avoid that we get too many double
                // sided connections we check again if we still don't have any connection for that node address.
                Optional<Connection> existingConnection = findExistingConnection(peersNodeAddress);
                existingConnection.ifPresent(c -> {
                    log.debug("We found in the meantime a connection for peersNodeAddress {}, " +
                                    "so we use that for sending the message.\n" +
                                    "That can happen if Tor needs long for creating a new outbound connection.\n" +
                                    "We might have got a new inbound or outbound connection.",
                            peersNodeAddress.getFullAddress());
                    try {
                        socket.close();
                    } catch (Throwable throwable) {
                        log.error("Error at closing socket {}", throwable.toString());
                    }
                });

                Connection connection = existingConnection.orElse(new OutboundConnection(socket,
                        this,
                        this,
                        peersNodeAddress,
                        networkProtoResolver));
                connection.sendMessage(networkEnvelope);
                return connection;
            } catch (Throwable throwable) {
                if (!(throwable instanceof IOException)) {
                    log.warn(throwable.getMessage());
                }
                throw throwable;
            }
        });

        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(Connection connection) {
                UserThread.execute(() -> resultFuture.set(connection));
            }

            public void onFailure(@NotNull Throwable throwable) {
                log.debug("onFailure at sendMessage: peersNodeAddress={}\n\tmessage={}\n\tthrowable={}",
                        peersNodeAddress, networkEnvelope.getClass().getSimpleName(), throwable.toString());
                UserThread.execute(() -> resultFuture.setException(throwable));
            }
        }, MoreExecutors.directExecutor());

        return resultFuture;
    }

    protected Optional<Connection> findExistingConnection(NodeAddress peersNodeAddress) {
        Optional<Connection> outboundConnection = getOutboundConnection(peersNodeAddress);
        if (outboundConnection.isPresent()) {
            return outboundConnection;
        }

        return getInboundConnection(peersNodeAddress);
    }

    private Optional<Connection> getInboundConnection(@NotNull NodeAddress peer) {
        Optional<InboundConnection> inboundConnectionOptional = lookupInBoundConnection(peer);
        if (inboundConnectionOptional.isPresent()) {
            InboundConnection connection = inboundConnectionOptional.get();
            log.trace("We have found a connection in inBoundConnections. Connection.uid={}", connection.getUid());
            if (connection.isStopped()) {
                log.warn("We have a connection which is already stopped in inBoundConnections. Connection.uid=" + connection.getUid());
                inBoundConnections.remove(connection);
                return Optional.empty();
            } else {
                return Optional.of(connection);
            }
        } else {
            return Optional.empty();
        }
    }

    private Optional<Connection> getOutboundConnection(@NotNull NodeAddress peersNodeAddress) {
        Optional<OutboundConnection> outboundConnectionOptional = lookupOutBoundConnection(peersNodeAddress);
        if (outboundConnectionOptional.isPresent()) {
            OutboundConnection connection = outboundConnectionOptional.get();
            log.trace("We have found a connection in outBoundConnections. Connection.uid={}", connection.getUid());
            if (connection.isStopped()) {
                log.warn("We have a connection which is already stopped in outBoundConnections. Connection.uid=" + connection.getUid());
                outBoundConnections.remove(connection);
                return Optional.empty();
            } else {
                return Optional.of(connection);
            }
        } else {
            return Optional.empty();
        }
    }

    private Optional<OutboundConnection> lookupOutBoundConnection(NodeAddress peersNodeAddress) {
        log.trace("lookupOutboundConnection for peersNodeAddress={}", peersNodeAddress.getFullAddress());
        printOutBoundConnections();
        return outBoundConnections.stream()
                .filter(connection -> connection.getPeersNodeAddressOptional().isPresent() &&
                        peersNodeAddress.equals(connection.getPeersNodeAddressOptional().get())).findAny();
    }

    private Optional<InboundConnection> lookupInBoundConnection(NodeAddress peersNodeAddress) {
        log.trace("lookupInboundConnection for peersNodeAddress={}", peersNodeAddress.getFullAddress());
        printInboundConnections();
        return inBoundConnections.stream()
                .filter(connection -> connection.getPeersNodeAddressOptional().isPresent() &&
                        peersNodeAddress.equals(connection.getPeersNodeAddressOptional().get())).findAny();
    }

    private void printOutBoundConnections() {
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("outBoundConnections size()=")
                    .append(outBoundConnections.size()).append("\n\toutBoundConnections=");
            outBoundConnections.forEach(e -> sb.append(e).append("\n\t"));
            log.debug(sb.toString());
        }
    }

    private void printInboundConnections() {
        if (log.isDebugEnabled()) {
            StringBuilder sb = new StringBuilder("inBoundConnections size()=")
                    .append(inBoundConnections.size()).append("\n\tinBoundConnections=");
            inBoundConnections.forEach(e -> sb.append(e).append("\n\t"));
            log.debug(sb.toString());
        }
    }
}
