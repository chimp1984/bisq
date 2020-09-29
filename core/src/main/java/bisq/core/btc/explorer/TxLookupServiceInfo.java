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

import bisq.core.btc.explorer.mempoolspace.MemPoolSpaceRequest;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import lombok.Value;

@Value
public final class TxLookupServiceInfo {
    private final String address;
    private final Class<? extends TxLookupRequest> requestClass;


    public static final List<TxLookupServiceInfo> BTC_EXPLORER_APIS = new ArrayList<>(Arrays.asList(
            new TxLookupServiceInfo("http://mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion", MemPoolSpaceRequest.class), //wiz
            new TxLookupServiceInfo("http://mempool4t6mypeemozyterviq3i5de4kpoua65r3qkn5i3kknu5l2cad.onion", MemPoolSpaceRequest.class), //emzy
            new TxLookupServiceInfo("http://mempoolusb2f67qi7mz2it7n5e77a6komdzx6wftobcduxszkdfun2yd.onion", MemPoolSpaceRequest.class) //devin
    ));

    public static final List<TxLookupServiceInfo> BTC_EXPLORER_APIS_CLEAR_NET = new ArrayList<>(Arrays.asList(
            new TxLookupServiceInfo("https://mempool.space/tx", MemPoolSpaceRequest.class), //wiz
            new TxLookupServiceInfo("https://mempool.emzy.de", MemPoolSpaceRequest.class), //emzy
            new TxLookupServiceInfo("https://mempool.bisq.services", MemPoolSpaceRequest.class) //devin
    ));

    public TxLookupServiceInfo(String address, Class<? extends TxLookupRequest> requestClass) {
        this.address = address;
        this.requestClass = requestClass;
    }
}
