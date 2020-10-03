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

package bisq.core.trade.protocol;

import bisq.core.trade.Trade;
import bisq.core.trade.messages.CounterCurrencyTransferStartedMessage;
import bisq.core.trade.messages.DepositTxAndDelayedPayoutTxMessage;
import bisq.core.trade.messages.TradeMessage;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.MailboxMessage;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.SendMailboxMessageListener;
import bisq.network.p2p.messaging.DecryptedMailboxListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.network.NetworkEnvelope;
import bisq.common.taskrunner.Task;
import bisq.common.util.Tuple2;

import java.security.PublicKey;

import java.util.HashMap;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public abstract class TradeProtocol implements DecryptedDirectMessageListener, DecryptedMailboxListener {

    protected final ProcessModel processModel;
    protected final ProcessModelServiceProvider serviceProvider;
    protected final Trade trade;
    private final Map<String, Tuple2<TradeMessage, NodeAddress>> earlyTradeMessages = new HashMap<>();
    private final Map<String, Tuple2<AckMessage, NodeAddress>> earlyAckMessages = new HashMap<>();
    private Timer timeoutTimer;
    private boolean initialized;


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public TradeProtocol(ProcessModelServiceProvider serviceProvider, Trade trade) {
        this.serviceProvider = serviceProvider;
        this.trade = trade;
        this.processModel = trade.getProcessModel();

        processModel.applyTransient(serviceProvider, trade.getOffer());

        // We start listening just from the start as it could be that we receive a direct message just after the
        // p2p service is ready but the trade protocol is not initialized yet. So we cache those potential messages
        // and apply them once we get initialized.
        if (!trade.isWithdrawn()) {
            this.serviceProvider.getP2PService().addDecryptedDirectMessageListener(this);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    public void onInitialized() {
        serviceProvider.getP2PService().addDecryptedMailboxListener(this);
        serviceProvider.getP2PService().getMailboxMap().values()
                .stream().map(e -> e.second)
                .forEach(this::handleDecryptedMessageWithPubKey);

        // In case we received a direct message before we have been initialized we apply it now
        earlyTradeMessages.values().forEach(tuple -> onTradeMessage(tuple.first, tuple.second));
        earlyTradeMessages.clear();
        earlyAckMessages.values().forEach(tuple -> onAckMessage(tuple.first, tuple.second));
        earlyAckMessages.clear();

        trade.initialize(serviceProvider);

        initialized = true;
    }

    public void onWithdrawCompleted() {
        cleanup();
    }

    protected void onMailboxMessage(TradeMessage message, NodeAddress peerNodeAddress) {
        log.info("Received {} as MailboxMessage from {} with tradeId {} and uid {}",
                message.getClass().getSimpleName(), peerNodeAddress, message.getTradeId(), message.getUid());
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey message, NodeAddress peer) {
        NetworkEnvelope networkEnvelope = message.getNetworkEnvelope();
        if (networkEnvelope instanceof TradeMessage) {
            TradeMessage tradeMessage = (TradeMessage) networkEnvelope;
            if (isMyMessage(tradeMessage) && isPubKeyValid(message)) {
                if (initialized) {
                    onTradeMessage(tradeMessage, peer);
                } else {
                    earlyTradeMessages.put(tradeMessage.getUid(), new Tuple2<>(tradeMessage, peer));
                }
            }
        } else if (networkEnvelope instanceof AckMessage) {
            AckMessage ackMessage = (AckMessage) networkEnvelope;
            if (isMyMessage(ackMessage) && isPubKeyValid(message)) {
                if (initialized) {
                    onAckMessage(ackMessage, peer);
                } else {
                    earlyAckMessages.put(ackMessage.getUid(), new Tuple2<>(ackMessage, peer));
                }
            }
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedMailboxListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMailboxMessageAdded(DecryptedMessageWithPubKey message, NodeAddress peer) {
        handleDecryptedMessageWithPubKey(message, peer);
    }

    private void handleDecryptedMessageWithPubKey(DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        MailboxMessage mailboxMessage = (MailboxMessage) decryptedMessageWithPubKey.getNetworkEnvelope();
        NodeAddress senderNodeAddress = mailboxMessage.getSenderNodeAddress();
        handleDecryptedMessageWithPubKey(decryptedMessageWithPubKey, senderNodeAddress);
    }

    protected void handleDecryptedMessageWithPubKey(DecryptedMessageWithPubKey decryptedMessageWithPubKey,
                                                    NodeAddress peer) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (networkEnvelope instanceof TradeMessage &&
                isMyMessage((TradeMessage) networkEnvelope) &&
                isPubKeyValid(decryptedMessageWithPubKey)) {
            TradeMessage tradeMessage = (TradeMessage) networkEnvelope;

            // We only remove here if we have already completed the trade.
            // Otherwise removal is done after successfully applied the task runner.
            if (trade.isWithdrawn()) {
                serviceProvider.getP2PService().removeEntryFromMailbox(decryptedMessageWithPubKey);
                log.info("Remove {} from the P2P network.", tradeMessage.getClass().getSimpleName());
                return;
            }

            onMailboxMessage(tradeMessage, peer);
        } else if (networkEnvelope instanceof AckMessage &&
                isMyMessage((AckMessage) networkEnvelope) &&
                isPubKeyValid(decryptedMessageWithPubKey)) {
            if (!trade.isWithdrawn()) {
                // We only apply the msg if we have not already completed the trade
                onAckMessage((AckMessage) networkEnvelope, peer);
            }
            // In any case we remove the msg
            serviceProvider.getP2PService().removeEntryFromMailbox(decryptedMessageWithPubKey);
            log.info("Remove {} from the P2P network.", networkEnvelope.getClass().getSimpleName());
        }
    }

    public void removeMailboxMessageAfterProcessing(TradeMessage tradeMessage) {
        TradingPeer tradingPeer = processModel.getTradingPeer();
        if (tradeMessage instanceof MailboxMessage &&
                tradingPeer != null &&
                tradingPeer.getPubKeyRing() != null &&
                tradingPeer.getPubKeyRing().getSignaturePubKey() != null) {
            PublicKey sigPubKey = tradingPeer.getPubKeyRing().getSignaturePubKey();
            // We reconstruct the DecryptedMessageWithPubKey from the message and the peers signature pubKey
            DecryptedMessageWithPubKey decryptedMessageWithPubKey = new DecryptedMessageWithPubKey(tradeMessage, sigPubKey);
            serviceProvider.getP2PService().removeEntryFromMailbox(decryptedMessageWithPubKey);
            log.info("Remove {} from the P2P network.", tradeMessage.getClass().getSimpleName());
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected abstract void onTradeMessage(TradeMessage message, NodeAddress peer);


    ///////////////////////////////////////////////////////////////////////////////////////////
    // FluentProtocol
    ///////////////////////////////////////////////////////////////////////////////////////////

    // We log an error if condition is not met and call the protocol error handler
    protected FluentProtocol expect(FluentProtocol.Condition condition) {
        return new FluentProtocol(this)
                .condition(condition)
                .resultHandler(result -> {
                    if (!result.isValid()) {
                        log.error(result.getInfo());
                        handleTaskRunnerFault(null,
                                result.name(),
                                result.getInfo());
                    }
                });
    }

    // We execute only if condition is met but do not log an error.
    protected FluentProtocol given(FluentProtocol.Condition condition) {
        return new FluentProtocol(this)
                .condition(condition);
    }

    protected FluentProtocol.Condition phase(Trade.Phase expectedPhase) {
        return new FluentProtocol.Condition(trade).phase(expectedPhase);
    }

    protected FluentProtocol.Condition anyPhase(Trade.Phase... expectedPhases) {
        return new FluentProtocol.Condition(trade).anyPhase(expectedPhases);
    }

    @SafeVarargs
    public final FluentProtocol.Setup tasks(Class<? extends Task<Trade>>... tasks) {
        return new FluentProtocol.Setup(this, trade).tasks(tasks);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // ACK msg
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void onAckMessage(AckMessage ackMessage, NodeAddress peer) {
        // We handle the ack for CounterCurrencyTransferStartedMessage and DepositTxAndDelayedPayoutTxMessage
        // as we support automatic re-send of the msg in case it was not ACKed after a certain time
        if (ackMessage.getSourceMsgClassName().equals(CounterCurrencyTransferStartedMessage.class.getSimpleName())) {
            processModel.setPaymentStartedAckMessage(ackMessage);
        } else if (ackMessage.getSourceMsgClassName().equals(DepositTxAndDelayedPayoutTxMessage.class.getSimpleName())) {
            processModel.setDepositTxSentAckMessage(ackMessage);
        }

        if (ackMessage.isSuccess()) {
            log.info("Received AckMessage for {} from {} with tradeId {} and uid {}",
                    ackMessage.getSourceMsgClassName(), peer, trade.getId(), ackMessage.getSourceUid());
        } else {
            log.warn("Received AckMessage with error state for {} from {} with tradeId {} and errorMessage={}",
                    ackMessage.getSourceMsgClassName(), peer, trade.getId(), ackMessage.getErrorMessage());
        }
    }

    protected void sendAckMessage(TradeMessage message, boolean result, @Nullable String errorMessage) {
        PubKeyRing peersPubKeyRing = processModel.getTradingPeer().getPubKeyRing();
        if (peersPubKeyRing == null) {
            log.error("We cannot send the ACK message as peersPubKeyRing is null");
            return;
        }

        String tradeId = message.getTradeId();
        String sourceUid = message.getUid();
        AckMessage ackMessage = new AckMessage(processModel.getMyNodeAddress(),
                AckMessageSourceType.TRADE_MESSAGE,
                message.getClass().getSimpleName(),
                sourceUid,
                tradeId,
                result,
                errorMessage);
        // If there was an error during offer verification, the tradingPeerNodeAddress of the trade might not be set yet.
        // We can find the peer's node address in the processModel's tempTradingPeerNodeAddress in that case.
        NodeAddress peer = trade.getTradingPeerNodeAddress() != null ?
                trade.getTradingPeerNodeAddress() :
                processModel.getTempTradingPeerNodeAddress();
        log.info("Send AckMessage for {} to peer {}. tradeId={}, sourceUid={}",
                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
        serviceProvider.getP2PService().sendEncryptedMailboxMessage(
                peer,
                peersPubKeyRing,
                ackMessage,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for {} arrived at peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("AckMessage for {} stored in mailbox for peer {}. tradeId={}, sourceUid={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid);
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for {} failed. Peer {}. tradeId={}, sourceUid={}, errorMessage={}",
                                ackMessage.getSourceMsgClassName(), peer, tradeId, sourceUid, errorMessage);
                    }
                }
        );
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Timeout
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void startTimeout(long timeoutSec) {
        stopTimeout();

        timeoutTimer = UserThread.runAfter(() -> {
            log.error("Timeout reached. TradeID={}, state={}, timeoutSec={}",
                    trade.getId(), trade.stateProperty().get(), timeoutSec);
            trade.setErrorMessage("Timeout reached. Protocol did not complete in " + timeoutSec + " sec.");
            cleanup();
        }, timeoutSec);
    }

    protected void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Task runner
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void handleTaskRunnerSuccess(TradeMessage message) {
        handleTaskRunnerSuccess(message, message.getClass().getSimpleName());
    }

    protected void handleTaskRunnerSuccess(FluentProtocol.Event event) {
        handleTaskRunnerSuccess(null, event.name());
    }

    protected void handleTaskRunnerFault(TradeMessage message, String errorMessage) {
        handleTaskRunnerFault(message, message.getClass().getSimpleName(), errorMessage);
    }

    protected void handleTaskRunnerFault(FluentProtocol.Event event, String errorMessage) {
        handleTaskRunnerFault(null, event.name(), errorMessage);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Validation
    ///////////////////////////////////////////////////////////////////////////////////////////

    private boolean isPubKeyValid(DecryptedMessageWithPubKey message) {
        // We can only validate the peers pubKey if we have it already. If we are the taker we get it from the offer
        // Otherwise it depends on the state of the trade protocol if we have received the peers pubKeyRing already.
        PubKeyRing peersPubKeyRing = processModel.getTradingPeer().getPubKeyRing();
        boolean isValid = true;
        if (peersPubKeyRing != null &&
                !message.getSignaturePubKey().equals(peersPubKeyRing.getSignaturePubKey())) {
            isValid = false;
            log.error("SignaturePubKey in message does not match the SignaturePubKey we have set for our trading peer.");
        }
        return isValid;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Private
    ///////////////////////////////////////////////////////////////////////////////////////////

    private void handleTaskRunnerSuccess(@Nullable TradeMessage message, String source) {
        log.info("TaskRunner successfully completed. Triggered from {}, tradeId={}", source, trade.getId());
        if (message != null) {
            sendAckMessage(message, true, null);

            // Once a taskRunner is completed we remove the mailbox message. To not remove it directly at the task
            // adds some resilience in case of minor errors, so after a restart the mailbox message can be applied
            // again.
            removeMailboxMessageAfterProcessing(message);
        }
    }

    void handleTaskRunnerFault(@Nullable TradeMessage message, String source, String errorMessage) {
        log.error("Task runner failed with error {}. Triggered from {}", errorMessage, source);

        if (message != null) {
            sendAckMessage(message, false, errorMessage);
        }
        cleanup();
    }

    private boolean isMyMessage(TradeMessage message) {
        return message.getTradeId().equals(trade.getId());
    }

    private boolean isMyMessage(AckMessage ackMessage) {
        return ackMessage.getSourceType() == AckMessageSourceType.TRADE_MESSAGE &&
                ackMessage.getSourceId().equals(trade.getId());
    }

    private void cleanup() {
        stopTimeout();
        // We do not remove the decryptedDirectMessageListener as in case of not critical failures we want allow to receive
        // follow-up messages still
    }
}
