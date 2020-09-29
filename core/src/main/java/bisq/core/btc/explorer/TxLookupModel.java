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

import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Value
public class TxLookupModel {
    private final String serviceAddress;
    private final String txId;

    TxLookupModel(String txId, String serviceAddress) {
        this.txId = txId;
        this.serviceAddress = serviceAddress;
    }

    @Override
    public String toString() {
        return "TxLookupModel{" +
                "\n     serviceAddress='" + serviceAddress + '\'' +
                ",\n     txId='" + txId + '\'' +
                "\n}";
    }
}
