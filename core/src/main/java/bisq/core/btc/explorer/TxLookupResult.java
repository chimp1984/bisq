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

import org.bitcoinj.core.Transaction;

import lombok.Getter;

import javax.annotation.Nullable;

public enum TxLookupResult {
    UNDEFINED,
    NOT_FOUND,
    // Fee txs
    IS_BSQ_FEE_TX(true),
    IS_BTC_FEE_TX(true),
    // non fee txs
    TX_FOUND(true),

    FAILED;

    @Nullable
    @Getter
    private Transaction tx;
    @Getter
    private final boolean terminal;
    @Getter
    private boolean inMemPool;
    @Getter
    private boolean confirmed;

    TxLookupResult() {
        terminal = false;
    }

    TxLookupResult(boolean terminal) {
        this.terminal = terminal;
    }

    TxLookupResult tx(Transaction tx) {
        this.tx = tx;
        return this;
    }

    TxLookupResult inMemPool(boolean inMemPool) {
        this.inMemPool = inMemPool;
        return this;
    }

    TxLookupResult confirmed(boolean confirmed) {
        this.confirmed = confirmed;
        return this;
    }

    public boolean isFeeTx() {
        return this == IS_BSQ_FEE_TX || this == IS_BTC_FEE_TX;
    }
}
