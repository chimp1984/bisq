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

package bisq.core.support;

import bisq.core.app.AppStartupState;
import bisq.core.btc.setup.WalletsSetup;
import bisq.core.support.messages.ChatMessage;
import bisq.core.support.messages.SupportMessage;

import bisq.network.p2p.AckMessage;
import bisq.network.p2p.AckMessageSourceType;
import bisq.network.p2p.DecryptedDirectMessageListener;
import bisq.network.p2p.DecryptedMessageWithPubKey;
import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.P2PService;
import bisq.network.p2p.SendMailboxMessageListener;
import bisq.network.p2p.messaging.DecryptedMailboxListener;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.PubKeyRing;
import bisq.common.proto.network.NetworkEnvelope;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@Slf4j
public abstract class SupportManager implements DecryptedDirectMessageListener, DecryptedMailboxListener {
    protected final P2PService p2PService;
    protected final WalletsSetup walletsSetup;
    protected final Map<String, Timer> delayMsgMap = new HashMap<>();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Constructor
    ///////////////////////////////////////////////////////////////////////////////////////////

    public SupportManager(AppStartupState appStartupState, P2PService p2PService, WalletsSetup walletsSetup) {
        this.p2PService = p2PService;
        this.walletsSetup = walletsSetup;

        appStartupState.walletAndNetworkReadyProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue) {
                onWalletAndNetworkReady();
            }
        });
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // API
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void onWalletAndNetworkReady() {
        p2PService.addDecryptedDirectMessageListener(this);

        p2PService.addDecryptedMailboxListener(this);
        p2PService.getMailboxMap().values()
                .stream().map(e -> e.second)
                .forEach(this::onDecryptedMessageWithPubKey);

        // In case we received a direct message before we have been initialized we apply it now
        p2PService.getDirectMessages().forEach(this::onDecryptedMessageWithPubKey);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedDirectMessageListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onDirectMessage(DecryptedMessageWithPubKey decryptedMessageWithPubKey, NodeAddress peer) {
        onDecryptedMessageWithPubKey(decryptedMessageWithPubKey);
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // DecryptedMailboxListener
    ///////////////////////////////////////////////////////////////////////////////////////////

    @Override
    public void onMailboxMessageAdded(DecryptedMessageWithPubKey message, NodeAddress peer) {
        onDecryptedMessageWithPubKey(message);
    }

    private void onDecryptedMessageWithPubKey(DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        NetworkEnvelope networkEnvelope = decryptedMessageWithPubKey.getNetworkEnvelope();
        if (networkEnvelope instanceof SupportMessage) {
            dispatchMessage((SupportMessage) networkEnvelope);
        } else if (networkEnvelope instanceof AckMessage) {
            onAckMessage((AckMessage) networkEnvelope, decryptedMessageWithPubKey);
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////
    // Abstract methods
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected abstract void dispatchMessage(SupportMessage networkEnvelope);

    public abstract NodeAddress getPeerNodeAddress(ChatMessage message);

    public abstract PubKeyRing getPeerPubKeyRing(ChatMessage message);

    public abstract SupportType getSupportType();

    public abstract boolean channelOpen(ChatMessage message);

    public abstract List<ChatMessage> getAllChatMessages();

    public abstract void addAndPersistChatMessage(ChatMessage message);

    public abstract void persist();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Delegates p2pService
    ///////////////////////////////////////////////////////////////////////////////////////////

    public boolean isBootstrapped() {
        return p2PService.isBootstrapped();
    }

    public NodeAddress getMyAddress() {
        return p2PService.getAddress();
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Message handler
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected void onChatMessage(ChatMessage chatMessage) {
        String tradeId = chatMessage.getTradeId();
        String uid = chatMessage.getUid();
        boolean channelOpen = channelOpen(chatMessage);
        if (!channelOpen) {
            log.debug("We got a chatMessage but we don't have a matching chat. TradeId = " + tradeId);
            if (!delayMsgMap.containsKey(uid)) {
                Timer timer = UserThread.runAfter(() -> onChatMessage(chatMessage), 1);
                delayMsgMap.put(uid, timer);
            } else {
                String msg = "We got a chatMessage after we already repeated to apply the message after a delay. That should never happen. TradeId = " + tradeId;
                log.warn(msg);
            }
            return;
        }

        cleanupRetryMap(uid);
        PubKeyRing receiverPubKeyRing = getPeerPubKeyRing(chatMessage);

        addAndPersistChatMessage(chatMessage);

        // We never get a errorMessage in that method (only if we cannot resolve the receiverPubKeyRing but then we
        // cannot send it anyway)
        if (receiverPubKeyRing != null)
            sendAckMessage(chatMessage, receiverPubKeyRing, true, null);
    }

    private void onAckMessage(AckMessage ackMessage,
                              @Nullable DecryptedMessageWithPubKey decryptedMessageWithPubKey) {
        if (ackMessage.getSourceType() == getAckMessageSourceType()) {
            if (ackMessage.isSuccess()) {
                log.info("Received AckMessage for {} with tradeId {} and uid {}",
                        ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getSourceUid());
            } else {
                log.warn("Received AckMessage with error state for {} with tradeId {} and errorMessage={}",
                        ackMessage.getSourceMsgClassName(), ackMessage.getSourceId(), ackMessage.getErrorMessage());
            }

            getAllChatMessages().stream()
                    .filter(msg -> msg.getUid().equals(ackMessage.getSourceUid()))
                    .forEach(msg -> {
                        if (ackMessage.isSuccess())
                            msg.setAcknowledged(true);
                        else
                            msg.setAckError(ackMessage.getErrorMessage());
                    });
            persist();

            if (decryptedMessageWithPubKey != null) {
                p2PService.removeEntryFromMailbox(decryptedMessageWithPubKey);
            }
        }
    }

    protected abstract AckMessageSourceType getAckMessageSourceType();


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Send message
    ///////////////////////////////////////////////////////////////////////////////////////////

    public ChatMessage sendChatMessage(ChatMessage message) {
        NodeAddress peersNodeAddress = getPeerNodeAddress(message);
        PubKeyRing receiverPubKeyRing = getPeerPubKeyRing(message);
        if (receiverPubKeyRing != null) {
            log.info("Send {} to peer {}. tradeId={}, uid={}",
                    message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());

            p2PService.sendEncryptedMailboxMessage(peersNodeAddress,
                    receiverPubKeyRing,
                    message,
                    new SendMailboxMessageListener() {
                        @Override
                        public void onArrived() {
                            log.info("{} arrived at peer {}. tradeId={}, uid={}",
                                    message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                            message.setArrived(true);
                            persist();
                        }

                        @Override
                        public void onStoredInMailbox() {
                            log.info("{} stored in mailbox for peer {}. tradeId={}, uid={}",
                                    message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid());
                            message.setStoredInMailbox(true);
                            persist();
                        }

                        @Override
                        public void onFault(String errorMessage) {
                            log.error("{} failed: Peer {}. tradeId={}, uid={}, errorMessage={}",
                                    message.getClass().getSimpleName(), peersNodeAddress, message.getTradeId(), message.getUid(), errorMessage);
                            message.setSendMessageError(errorMessage);
                            persist();
                        }
                    }
            );
        }

        return message;
    }

    protected void sendAckMessage(SupportMessage supportMessage, PubKeyRing peersPubKeyRing,
                                  boolean result, @Nullable String errorMessage) {
        String tradeId = supportMessage.getTradeId();
        String uid = supportMessage.getUid();
        AckMessage ackMessage = new AckMessage(p2PService.getNetworkNode().getNodeAddress(),
                getAckMessageSourceType(),
                supportMessage.getClass().getSimpleName(),
                uid,
                tradeId,
                result,
                errorMessage);
        final NodeAddress peersNodeAddress = supportMessage.getSenderNodeAddress();
        log.info("Send AckMessage for {} to peer {}. tradeId={}, uid={}",
                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, uid);
        p2PService.sendEncryptedMailboxMessage(
                peersNodeAddress,
                peersPubKeyRing,
                ackMessage,
                new SendMailboxMessageListener() {
                    @Override
                    public void onArrived() {
                        log.info("AckMessage for {} arrived at peer {}. tradeId={}, uid={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, uid);
                    }

                    @Override
                    public void onStoredInMailbox() {
                        log.info("AckMessage for {} stored in mailbox for peer {}. tradeId={}, uid={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, uid);
                    }

                    @Override
                    public void onFault(String errorMessage) {
                        log.error("AckMessage for {} failed. Peer {}. tradeId={}, uid={}, errorMessage={}",
                                ackMessage.getSourceMsgClassName(), peersNodeAddress, tradeId, uid, errorMessage);
                    }
                }
        );
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Protected
    ///////////////////////////////////////////////////////////////////////////////////////////

    protected boolean canProcessMessage(SupportMessage message) {
        return message.getSupportType() == getSupportType();
    }

    protected void cleanupRetryMap(String uid) {
        if (delayMsgMap.containsKey(uid)) {
            Timer timer = delayMsgMap.remove(uid);
            if (timer != null)
                timer.stop();
        }
    }
}
