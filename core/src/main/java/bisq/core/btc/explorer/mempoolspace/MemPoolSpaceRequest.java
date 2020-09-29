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

package bisq.core.btc.explorer.mempoolspace;

import bisq.core.btc.explorer.TxLookupHttpClient;
import bisq.core.btc.explorer.TxLookupModel;
import bisq.core.btc.explorer.TxLookupRequest;
import bisq.core.btc.explorer.TxLookupResult;

import bisq.network.Socks5ProxyProvider;

import bisq.common.UserThread;
import bisq.common.app.Version;
import bisq.common.handlers.FaultHandler;
import bisq.common.util.Utilities;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.function.Consumer;

import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.NotNull;

/**
 * Request a explorer API (a mem pool space instance) for the transaction ID
 */
@Slf4j
@EqualsAndHashCode
public class MemPoolSpaceRequest implements TxLookupRequest {

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Class fields
    ///////////////////////////////////////////////////////////////////////////////////////////

    private final ListeningExecutorService executorService = Utilities.getListeningExecutorService(
            "TxLookupRequest", 3, 5, 10 * 60);

    private final MemPoolSpaceParser parser;
    private final TxLookupModel model;
    private final TxLookupHttpClient httpClient;
    private boolean terminated;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public MemPoolSpaceRequest(Socks5ProxyProvider socks5ProxyProvider, TxLookupModel model) {
        this.parser = new MemPoolSpaceParser();
        this.model = model;

        httpClient = new TxLookupHttpClient(socks5ProxyProvider);

        // localhost, LAN address, or *.local FQDN starts with http://, don't use Tor
        if (model.getServiceAddress().regionMatches(0, "http:", 0, 5)) {
            httpClient.setBaseUrl(model.getServiceAddress());
            httpClient.setIgnoreSocks5Proxy(true);
            // any non-onion FQDN starts with https://, use Tor
        } else if (model.getServiceAddress().regionMatches(0, "https:", 0, 6)) {
            httpClient.setBaseUrl(model.getServiceAddress());
            httpClient.setIgnoreSocks5Proxy(false);
            // it's a raw onion so add http:// and use Tor proxy
        } else {
            httpClient.setBaseUrl("http://" + model.getServiceAddress());
            httpClient.setIgnoreSocks5Proxy(false);
        }

    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void requestFromService(Consumer<TxLookupResult> resultHandler, FaultHandler faultHandler) {
        if (terminated) {
            log.warn("Not starting {} as we have already terminated.", this);
            return;
        }

        // Timeout handing is delegated to the connection timeout handling in httpClient.
        ListenableFuture<TxLookupResult> future = executorService.submit(() -> {
            Thread.currentThread().setName(this.info());
            // TODO is API implemented in mempool space? Did not find anything yet...
            String param = "api/v1/tx/" + model.getTxId();
            log.info("Param {} for {}", param, this);
            String json = httpClient.requestWithGET(param, "User-Agent", "bisq/" + Version.VERSION);
            try {
                String prettyJson = new GsonBuilder().setPrettyPrinting().create().toJson(new JsonParser().parse(json));
                log.info("Response json from {}\n{}", this, prettyJson);
            } catch (Throwable error) {
                log.error("Pretty print caused a {}: raw json={}", error, json);
            }

            TxLookupResult result = parser.parse(model, json);
            log.info("TxLookupResult from {}\n{}", this, result);
            return result;
        });

        Futures.addCallback(future, new FutureCallback<>() {
            public void onSuccess(TxLookupResult result) {
                if (terminated) {
                    return;
                }
                UserThread.execute(() -> resultHandler.accept(result));
            }

            public void onFailure(@NotNull Throwable throwable) {
                if (terminated) {
                    return;
                }
                String errorMessage = info() + " failed with error " + throwable.toString();
                faultHandler.handleFault(errorMessage, throwable);
                UserThread.execute(() -> resultHandler.accept(TxLookupResult.FAILED));
            }
        }, MoreExecutors.directExecutor());
    }

    public void terminate() {
        terminated = true;
    }

    @Override
    public String toString() {
        return "Request at: " + model.getServiceAddress() + " for " + model.toString();
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private String info() {
        return "TxLookupRequest " + Utilities.getShortId(model.getTxId()) + " @ " + model.getServiceAddress().substring(0, 6);
    }
}
