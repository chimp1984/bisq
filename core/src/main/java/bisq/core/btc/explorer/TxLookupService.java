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

package bisq.core.btc.explorer;

import bisq.network.Socks5ProxyProvider;

import bisq.common.config.Config;

import javax.inject.Inject;
import javax.inject.Singleton;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import lombok.extern.slf4j.Slf4j;

/**
 * Requests from explorer APIs if a transaction is in mem-pool or confirmed. We use multiple parallel requests and the
 * first positive response will be returned to the requester.
 */
@Slf4j
@Singleton
public class TxLookupService {
    private final Socks5ProxyProvider socks5ProxyProvider;
    private final boolean useLocalhostForP2P;
    private final Map<String, TxLookupRequestsPerTxId> servicesByTxId = new HashMap<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Inject
    public TxLookupService(Socks5ProxyProvider socks5ProxyProvider, Config config) {
        this.socks5ProxyProvider = socks5ProxyProvider;
        this.useLocalhostForP2P = config.useLocalhostForP2P;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void shutDown() {
        servicesByTxId.values().forEach(TxLookupRequestsPerTxId::terminate);
        servicesByTxId.clear();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void startRequests(String txId, Consumer<TxLookupResult> resultHandler) {
        TxLookupRequestsPerTxId service = new TxLookupRequestsPerTxId(socks5ProxyProvider, txId, useLocalhostForP2P);
        servicesByTxId.put(txId, service);
        service.requestFromAllServices(
                result -> {
                    if (result.isTerminal()) {
                        servicesByTxId.remove(txId);
                    }
                    resultHandler.accept(result);
                },
                (errorMessage, throwable) -> {
                    log.error(errorMessage);
                });
    }

}
