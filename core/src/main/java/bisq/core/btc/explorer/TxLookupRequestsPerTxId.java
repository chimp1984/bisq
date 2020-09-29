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

import bisq.common.handlers.FaultHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import java.lang.reflect.InvocationTargetException;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Handles lookup requests for multiple services per txId. Once one returns with a terminal result (mempool or confirmed)
 * we terminate all pending requests.
 */
@Slf4j
class TxLookupRequestsPerTxId {
    @Getter
    private final Socks5ProxyProvider socks5ProxyProvider;
    private final String txId;
    private final boolean useLocalhostForP2P;
    private final Set<TxLookupRequest> requests = new HashSet<>();
    private boolean terminated;
    private long startDate;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    TxLookupRequestsPerTxId(Socks5ProxyProvider socks5ProxyProvider, String txId, boolean useLocalhostForP2P) {
        this.socks5ProxyProvider = socks5ProxyProvider;
        this.txId = txId;
        this.useLocalhostForP2P = useLocalhostForP2P;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestFromAllServices(Consumer<TxLookupResult> resultHandler, FaultHandler faultHandler) {
        startDate = System.currentTimeMillis();
        List<TxLookupServiceInfo> serviceAddresses = useLocalhostForP2P ?
                TxLookupServiceInfo.BTC_EXPLORER_APIS_CLEAR_NET :
                TxLookupServiceInfo.BTC_EXPLORER_APIS;
        for (TxLookupServiceInfo serviceAddress : serviceAddresses) {
            TxLookupModel model = new TxLookupModel(txId, serviceAddress.getAddress());
            try {
                TxLookupRequest request = serviceAddress.getRequestClass().getDeclaredConstructor(Socks5ProxyProvider.class, TxLookupModel.class)
                        .newInstance(socks5ProxyProvider, model);

                log.info("{} created", request);
                requests.add(request);

                request.requestFromService(result -> {
                            if (terminated) {
                                return;
                            }

                            if (result.isTerminal()) {
                                terminate();
                            }

                            resultHandler.accept(result);
                        },
                        faultHandler);
            } catch (InstantiationException | IllegalAccessException |
                    InvocationTargetException | NoSuchMethodException e) {
                e.printStackTrace();
                faultHandler.handleFault("Could not construct TxLookupRequest", e);
            }
        }
    }

    public void terminate() {
        terminated = true;
        requests.forEach(TxLookupRequest::terminate);
        requests.clear();
    }

    public long started() {
        return startDate;
    }
}
