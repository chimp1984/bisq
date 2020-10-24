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

package bisq.network.p2p.network.tor;

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.Utils;
import bisq.network.p2p.network.NetworkNode;
import bisq.network.p2p.network.SetupListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.proto.network.NetworkProtoResolver;

import org.berndpruenster.netlayer.tor.HiddenServiceSocket;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;
import org.berndpruenster.netlayer.tor.TorSocket;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.security.SecureRandom;

import java.net.Socket;

import java.io.IOException;

import java.util.Base64;
import java.util.Date;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNode extends NetworkNode {
    private static final long SHUT_DOWN_TIMEOUT_SEC = 3;

    private final boolean streamIsolation;
    private final TorMode torMode;

    @Nullable
    private HiddenServiceSocket hiddenServiceSocket;
    @Nullable
    private Timer shutDownTimeoutTimer;
    @Nullable
    private Tor tor;
    @Nullable
    private Socks5Proxy socksProxy;
    private boolean shutDownCompleted;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TorNetworkNode(int servicePort,
                          NetworkProtoResolver networkProtoResolver,
                          boolean useStreamIsolation,
                          TorMode torMode) {
        super(servicePort, networkProtoResolver);

        this.streamIsolation = useStreamIsolation;
        this.torMode = torMode;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void start(@Nullable SetupListener setupListener) {
        torMode.doRollingBackup();

        if (setupListener != null) {
            addSetupListener(setupListener);
        }

        createTorAndHiddenService();
    }

    public void shutDown(@Nullable Runnable completeHandler) {
        shutDownCompleted = false;
        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("Tor still not shut down after {} sec", SHUT_DOWN_TIMEOUT_SEC);
            completeShutDown(completeHandler);
        }, SHUT_DOWN_TIMEOUT_SEC);


        // We only shut down tor if it is created from our application. If we used the systems tor we leave it running.
        if (tor != null && torMode instanceof NewTor) {
            try {
                tor.shutdown();
                Tor.setDefault(null);
                log.info("Tor shut down completed");
            } catch (Throwable e) {
                log.error("Shutdown torNetworkNode failed with exception: {}", e.getMessage());
                e.printStackTrace();
            }
        }

        completeShutDown(completeHandler);
    }

    private void completeShutDown(Runnable completeHandler) {
        if (shutDownCompleted) {
            return;
        }
        shutDownCompleted = true;

        if (shutDownTimeoutTimer != null) {
            shutDownTimeoutTimer.stop();
            shutDownTimeoutTimer = null;
        }
        log.info("TorNetworkNode shutdown complete");

        // Once we are done we start shut down routine in base class
        super.shutDown(completeHandler);
    }

    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        checkArgument(peerNodeAddress.getHostName().endsWith(".onion"), "PeerAddress is not an onion address");
        // If streamId is null stream isolation gets deactivated.
        // Hidden services use stream isolation by default so we pass null.
        return new TorSocket(peerNodeAddress.getHostName(), peerNodeAddress.getPort(), null);
    }

    @Nullable
    public Socks5Proxy getSocksProxy() {
        try {
            if (socksProxy == null || streamIsolation) {
                socksProxy = tor != null ?
                        tor.getProxy(getStreamId()) :
                        null;
            }
            return socksProxy;
        } catch (TorCtlException e) {
            log.error("TorCtlException at getSocksProxy: {}", e.toString());
            e.printStackTrace();
            return null;
        } catch (Throwable t) {
            log.error("Error at getSocksProxy: {}", t.toString());
            return null;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Create tor and hidden service
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTorAndHiddenService() {
        ListenableFuture<Void> future = getExecutorService().submit(() -> {
            try {
                tor = torMode.getTor();
                Tor.setDefault(tor);

                // Start hidden service
                long ts = new Date().getTime();
                hiddenServiceSocket = new HiddenServiceSocket(Utils.findFreeSystemPort(), torMode.getHiddenServiceDirectory(), servicePort);

                UserThread.execute(() -> {
                    NodeAddress nodeAddress = new NodeAddress(hiddenServiceSocket.getServiceName() + ":" +
                            hiddenServiceSocket.getHiddenServicePort());
                    nodeAddressProperty.set(nodeAddress);

                    setupListeners.forEach(SetupListener::onTorNodeReady);
                });
                hiddenServiceSocket.addReadyListener(socket -> {
                    log.info("\n################################################################\n" +
                                    "Tor hidden service published after {} ms. Socket={}\n" +
                                    "################################################################",
                            new Date().getTime() - ts, hiddenServiceSocket);
                    UserThread.execute(() -> {
                        NodeAddress nodeAddress = new NodeAddress(hiddenServiceSocket.getServiceName() + ":" +
                                hiddenServiceSocket.getHiddenServicePort());
                        nodeAddressProperty.set(nodeAddress);
                        startServer(hiddenServiceSocket);

                        setupListeners.forEach(SetupListener::onHiddenServicePublished);
                    });
                    return null;
                });
            } catch (TorCtlException e) {
                if (e.getCause() instanceof IOException) {
                    setupFailed(e);
                } else {
                    requestCustomBridges(e);
                }
            } catch (Throwable e) {
                setupFailed(e);
            }

            return null;
        });
        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(Void ignore) {
            }

            public void onFailure(@NotNull Throwable e) {
                setupFailed(e);
            }
        }, MoreExecutors.directExecutor());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Nullable
    private String getStreamId() {
        if (streamIsolation) {
            // Create a random string
            byte[] bytes = new byte[512]; // note that getProxy does Sha256 that string anyways
            new SecureRandom().nextBytes(bytes);
            return Base64.getEncoder().encodeToString(bytes);
        }

        return null;
    }

    private void requestCustomBridges(TorCtlException e) {
        log.error("Exception at starting Tor: {}", e.toString());
        UserThread.execute(() -> {
            // We trigger a popup for telling the user to try with custom bridges and restart
            setupListeners.forEach(SetupListener::onRequestCustomBridges);
            shutDown(null);
        });
    }

    private void setupFailed(@NotNull Throwable e) {
        log.error("Exception at starting Tor: {}", e.toString());
        UserThread.execute(() -> {
            setupListeners.forEach(listener -> listener.onSetupFailed(e));
            shutDown(null);
        });
    }
}
