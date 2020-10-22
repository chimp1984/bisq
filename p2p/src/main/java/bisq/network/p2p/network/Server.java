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

import bisq.common.proto.network.NetworkProtoResolver;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;

import java.io.IOException;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class Server implements Runnable {
    private final ServerSocket serverSocket;
    private final MessageListener messageListener;
    private final ConnectionListener connectionListener;
    private final NetworkProtoResolver networkProtoResolver;
    private final Set<Connection> connections = new CopyOnWriteArraySet<>();
    private final AtomicBoolean terminated = new AtomicBoolean();

    public Server(ServerSocket serverSocket,
                  MessageListener messageListener,
                  ConnectionListener connectionListener,
                  NetworkProtoResolver networkProtoResolver) {
        this.serverSocket = serverSocket;
        this.messageListener = messageListener;
        this.connectionListener = connectionListener;
        this.networkProtoResolver = networkProtoResolver;
    }

    @Override
    public void run() {
        try {
            Thread.currentThread().setName("Server-" + serverSocket.getLocalPort());
            while (!terminated.get() && !Thread.currentThread().isInterrupted()) {
                log.debug("Ready to accept new clients on port {}", serverSocket.getLocalPort());
                Socket socket = serverSocket.accept();
                if (!terminated.get() && !Thread.currentThread().isInterrupted()) {
                    log.debug("Accepted new client on localPort {} / port {} ", socket.getLocalPort(), socket.getPort());
                    InboundConnection connection = new InboundConnection(socket,
                            messageListener,
                            connectionListener,
                            networkProtoResolver);

                    log.debug("\n\n%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n" +
                                    "Server created new inbound connection:\n" +
                                    "localPort/port={}/{}\n" +
                                    "connection.uid={}\n" +
                                    "%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%%\n",
                            serverSocket.getLocalPort(), socket.getPort(), connection.getUid());

                    if (!terminated.get()) {
                        connections.add(connection);
                    } else {
                        connection.shutDown(CloseConnectionReason.APP_SHUT_DOWN);
                    }
                }
            }
        } catch (IOException e) {
            if (!terminated.get()) {
                e.printStackTrace();
            }
        } catch (Throwable t) {
            log.error(t.getMessage());
            t.printStackTrace();
        }
    }

    public void shutDown() {
        if (terminated.get()) {
            log.warn("We got already terminated");
            return;
        }

        terminated.set(true);

        connections.forEach(c -> c.shutDown(CloseConnectionReason.APP_SHUT_DOWN));
        connections.clear();

        try {
            if (!serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (SocketException e) {
            log.debug("SocketException at shutdown might be expected {}", e.getMessage());
        } catch (IOException e) {
            log.debug("Exception at shutdown. {}", e.getMessage());
        } finally {
            log.debug("Server shutdown complete");
        }
    }
}
