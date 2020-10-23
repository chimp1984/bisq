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

import org.fxmisc.easybind.EasyBind;
import org.fxmisc.easybind.monadic.MonadicBinding;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

import java.security.SecureRandom;

import java.net.Socket;

import java.io.IOException;

import java.util.Base64;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class TorNetworkNode extends NetworkNode {
    private static final int MAX_RESTART_ATTEMPTS = 5;
    private static final long SHUT_DOWN_TIMEOUT = 5;

    private final boolean streamIsolation;
    private final TorMode torMode;

    private HiddenServiceSocket hiddenServiceSocket;
    private Timer shutDownTimeoutTimer;
    private int restartCounter;
    @SuppressWarnings("FieldCanBeLocal")
    private MonadicBinding<Boolean> allShutDown;
    private Tor tor;
    private Socks5Proxy socksProxy;
    private ListenableFuture<Void> torStartupFuture;

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

        createTorAndHiddenService(Utils.findFreeSystemPort(), servicePort);
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
                tor = Tor.getDefault();
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

    public void shutDown(@Nullable Runnable shutDownCompleteHandler) {
        // this one is executed synchronously
        BooleanProperty networkNodeShutDown = networkNodeShutDown();
        // this one is committed as a thread to the executor
        BooleanProperty torNetworkNodeShutDown = torNetworkNodeShutDown();
        BooleanProperty shutDownTimerTriggered = shutDownTimerTriggered();
        // Need to store allShutDown to not get garbage collected
        allShutDown = EasyBind.combine(torNetworkNodeShutDown, networkNodeShutDown, shutDownTimerTriggered,
                (a, b, c) -> (a && b) || c);
        allShutDown.subscribe((observable, oldValue, newValue) -> {
            if (newValue) {
                shutDownTimeoutTimer.stop();
                long ts = System.currentTimeMillis();
                try {
                    MoreExecutors.shutdownAndAwaitTermination(executorService, 500, TimeUnit.MILLISECONDS);
                    log.debug("Shutdown executorService done after {} ms.", System.currentTimeMillis() - ts);
                } catch (Throwable t) {
                    log.error("Shutdown executorService failed with exception: {}", t.getMessage());
                    t.printStackTrace();
                } finally {
                    if (shutDownCompleteHandler != null)
                        shutDownCompleteHandler.run();
                }
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Create tor and hidden service
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void createTorAndHiddenService(int localPort, int servicePort) {
        torStartupFuture = executorService.submit(() -> {
            try {
                long ts = new Date().getTime();
                Tor.setDefault(torMode.getTor());

                // Start hidden service
                hiddenServiceSocket = new HiddenServiceSocket(localPort, torMode.getHiddenServiceDirectory(), servicePort);

                UserThread.execute(() -> {
                    nodeAddressProperty.set(new NodeAddress(hiddenServiceSocket.getServiceName() + ":" + hiddenServiceSocket.getHiddenServicePort()));
                    setupListeners.forEach(SetupListener::onTorNodeReady);
                });
                hiddenServiceSocket.addReadyListener(socket -> {
                    try {
                        log.info("\n################################################################\n" +
                                        "Tor hidden service published after {} ms. Socket={}\n" +
                                        "################################################################",
                                new Date().getTime() - ts, socket);

                        UserThread.execute(() -> {
                            nodeAddressProperty.set(new NodeAddress(hiddenServiceSocket.getServiceName() + ":" + hiddenServiceSocket.getHiddenServicePort()));
                            startServer(socket);
                            setupListeners.forEach(SetupListener::onHiddenServicePublished);
                        });
                    } catch (Throwable e) {
                        log.error(e.toString());
                        e.printStackTrace();
                    }
                    return null;
                });
                log.info("It will take some time for the HS to be reachable (~40 seconds). You will be notified about this");
            } catch (TorCtlException e) {
                String msg = e.getCause() != null ? e.getCause().toString() : e.toString();
                log.error("Tor node creation failed: {}", msg);
                if (e.getCause() instanceof IOException) {
                    // Since we cannot connect to Tor, we cannot do anything.
                    // Furthermore, we have no hidden services started yet, so there is no graceful
                    // shutdown needed either
                    UserThread.execute(() -> setupListeners.forEach(listener -> listener.onSetupFailed(new RuntimeException(msg))));
                } else {
                    UserThread.execute(() -> restartTor(e.getMessage()));
                }
            } catch (IOException e) {
                log.error("Could not connect to running Tor: {}", e.getMessage());
                // Since we cannot connect to Tor, we cannot do anything.
                // Furthermore, we have no hidden services started yet, so there is no graceful
                // shutdown needed either
                UserThread.execute(() -> setupListeners.forEach(listener -> listener.onSetupFailed(new RuntimeException(e.getMessage()))));
            } catch (Throwable ignore) {
            }

            return null;
        });
        Futures.addCallback(torStartupFuture, new FutureCallback<>() {
            public void onSuccess(Void ignore) {
            }

            public void onFailure(@NotNull Throwable throwable) {
                log.error("Hidden service creation failed: {}", throwable.toString());
                UserThread.execute(() -> setupListeners.forEach(listener -> listener.onSetupFailed(new RuntimeException(throwable.toString()))));
            }
        }, MoreExecutors.directExecutor());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Restart
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void restartTor(String errorMessage) {
        log.info("Restart Tor");
        restartCounter++;
        if (restartCounter <= MAX_RESTART_ATTEMPTS) {
            setupListeners.forEach(SetupListener::onRequestCustomBridges);
            log.warn("We stop tor as starting tor with the default bridges failed. We request user to add custom bridges.");
            shutDown(null);
        } else {
            String msg = "We tried to restart Tor " + restartCounter +
                    " times, but it continued to fail with error message:\n" +
                    errorMessage + "\n\n" +
                    "Please check your internet connection and firewall and try to start again.";
            log.error(msg);
            throw new RuntimeException(msg);
        }
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

    private BooleanProperty torNetworkNodeShutDown() {
        BooleanProperty done = new SimpleBooleanProperty();
        try {
            tor = Tor.getDefault();
            if (tor != null) {
                log.info("Tor has been created already so we can shut it down.");
                tor.shutdown();
                log.info("Tor shut down completed");
            } else {
                log.info("Tor has not been created yet. We cancel the torStartupFuture.");
                torStartupFuture.cancel(true);
                log.info("torStartupFuture cancelled");
            }
        } catch (Throwable e) {
            log.error("Shutdown torNetworkNode failed with exception: {}", e.getMessage());
            e.printStackTrace();
        } finally {
            // We need to delay as otherwise our listener would not get called if shutdown completes in synchronous manner
            UserThread.execute(() -> done.set(true));
        }
        return done;
    }

    private BooleanProperty networkNodeShutDown() {
        BooleanProperty done = new SimpleBooleanProperty();
        UserThread.execute(() -> super.shutDown(() -> done.set(true)));
        return done;
    }

    private BooleanProperty shutDownTimerTriggered() {
        BooleanProperty done = new SimpleBooleanProperty();
        shutDownTimeoutTimer = UserThread.runAfter(() -> {
            log.error("A timeout occurred at shutDown");
            done.set(true);
        }, SHUT_DOWN_TIMEOUT);
        return done;
    }
}
