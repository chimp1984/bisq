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

package bisq.core.trade;

import bisq.core.btc.wallet.BtcWalletService;
import bisq.core.offer.Offer;
import bisq.core.trade.protocol.BuyerProtocol;

import bisq.network.p2p.NodeAddress;

import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.storage.Storage;

import org.bitcoinj.core.Coin;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

@Slf4j
public abstract class BuyerTrade extends Trade {

    public enum CancelTradeState {
        REQUEST_MSG_SENT,
        REQUEST_MSG_ARRIVED,
        REQUEST_MSG_IN_MAILBOX,
        REQUEST_MSG_SEND_FAILED,

        RECEIVED_ACCEPTED_MSG,
        PAYOUT_TX_SEEN_IN_NETWORK,
        RECEIVED_REJECTED_MSG
    }

    BuyerTrade(Offer offer,
               Coin tradeAmount,
               Coin txFee,
               Coin takerFee,
               boolean isCurrencyForTakerFeeBtc,
               long tradePrice,
               NodeAddress tradingPeerNodeAddress,
               @Nullable NodeAddress arbitratorNodeAddress,
               @Nullable NodeAddress mediatorNodeAddress,
               @Nullable NodeAddress refundAgentNodeAddress,
               Storage<? extends TradableList> storage,
               BtcWalletService btcWalletService) {
        super(offer,
                tradeAmount,
                txFee,
                takerFee,
                isCurrencyForTakerFeeBtc,
                tradePrice,
                tradingPeerNodeAddress,
                arbitratorNodeAddress,
                mediatorNodeAddress,
                refundAgentNodeAddress,
                storage,
                btcWalletService);
    }

    BuyerTrade(Offer offer,
               Coin txFee,
               Coin takerFee,
               boolean isCurrencyForTakerFeeBtc,
               @Nullable NodeAddress arbitratorNodeAddress,
               @Nullable NodeAddress mediatorNodeAddress,
               @Nullable NodeAddress refundAgentNodeAddress,
               Storage<? extends TradableList> storage,
               BtcWalletService btcWalletService) {
        super(offer,
                txFee,
                takerFee,
                isCurrencyForTakerFeeBtc,
                arbitratorNodeAddress,
                mediatorNodeAddress,
                refundAgentNodeAddress,
                storage,
                btcWalletService);
    }

    public void onFiatPaymentStarted(ResultHandler resultHandler, ErrorMessageHandler errorMessageHandler) {
        checkArgument(tradeProtocol instanceof BuyerProtocol, "Check failed:  tradeProtocol instanceof BuyerProtocol");
        ((BuyerProtocol) tradeProtocol).onFiatPaymentStarted(resultHandler, errorMessageHandler);
    }

    @Override
    public Coin getPayoutAmount() {
        checkNotNull(getTradeAmount(), "Invalid state: getTradeAmount() = null");

        return getOffer().getBuyerSecurityDeposit().add(getTradeAmount());
    }

    @Override
    public boolean wasCanceledTrade() {
        switch (buyersCancelTradeState) {
            case RECEIVED_ACCEPTED_MSG:
            case PAYOUT_TX_SEEN_IN_NETWORK:
                return true;
            default:
                return false;
        }
    }
}
