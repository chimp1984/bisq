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

import bisq.common.UserThread;
import bisq.common.proto.network.NetworkProtoResolver;

import com.runjva.sourceforge.jsocks.protocol.Socks5Proxy;

import java.net.ServerSocket;
import java.net.Socket;

import java.io.IOException;

import java.util.concurrent.TimeUnit;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

@Slf4j
public class LocalhostNetworkNode extends NetworkNode {
    @Setter
    private static int simulateTorNodeReady = 500;
    @Setter
    private static int simulateHiddenServiceReady = 500;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public LocalhostNetworkNode(int port, NetworkProtoResolver networkProtoResolver) {
        super(port, networkProtoResolver);
    }

    @Override
    public void start(@Nullable SetupListener setupListener) {
        if (setupListener != null) {
            addSetupListener(setupListener);
        }

        // Simulate tor connection delay
        UserThread.runAfter(() -> {
            nodeAddressProperty.set(new NodeAddress("localhost", servicePort));
            setupListeners.forEach(SetupListener::onTorNodeReady);

            // Simulate tor HS publishing delay
            UserThread.runAfter(() -> {
                try {
                    startServer(new ServerSocket(servicePort));
                    setupListeners.forEach(SetupListener::onHiddenServicePublished);
                } catch (IOException e) {
                    e.printStackTrace();
                    log.error("Exception at startServer: {}", e.getMessage());
                }
            }, simulateHiddenServiceReady, TimeUnit.MILLISECONDS);

        }, simulateTorNodeReady, TimeUnit.MILLISECONDS);
    }

    // Called from NetworkNode thread
    @Override
    protected Socket createSocket(NodeAddress peerNodeAddress) throws IOException {
        return new Socket(peerNodeAddress.getHostName(), peerNodeAddress.getPort());
    }

    @Nullable
    @Override
    public Socks5Proxy getSocksProxy() {
        return null;
    }
}
