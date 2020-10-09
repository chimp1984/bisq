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

package bisq.core.offer;

import bisq.core.trade.Tradable;

import bisq.network.p2p.NodeAddress;

import bisq.common.Timer;
import bisq.common.UserThread;
import bisq.common.crypto.CryptoUtils;
import bisq.common.crypto.Encryption;
import bisq.common.crypto.KeyStorage;
import bisq.common.crypto.PubKeyRing;
import bisq.common.crypto.Sig;
import bisq.common.proto.ProtoUtil;

import com.google.protobuf.ByteString;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.DSAParams;
import java.security.interfaces.DSAPrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.DSAPublicKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;

import java.math.BigInteger;

import java.util.Date;
import java.util.Optional;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

@EqualsAndHashCode
@Slf4j
public final class OpenOffer implements Tradable {
    // Timeout for offer reservation during takeoffer process. If deposit tx is not completed in that time we reset the offer to AVAILABLE state.
    private static final long TIMEOUT = 60;
    transient private Timer timeoutTimer;

    public enum State {
        AVAILABLE,
        RESERVED,
        CLOSED,
        CANCELED,
        DEACTIVATED
    }

    @Getter
    private final Offer offer;
    @Getter
    private State state;
    @Getter
    @Setter
    @Nullable
    private NodeAddress arbitratorNodeAddress;
    @Getter
    @Setter
    @Nullable
    private NodeAddress mediatorNodeAddress;

    // Added v1.2.0
    @Getter
    @Setter
    @Nullable
    private NodeAddress refundAgentNodeAddress;

    // Added 1.4.1
    @Getter
    @Nullable
    private final KeyPair signatureKeyPair;
    @Getter
    @Nullable
    private final KeyPair encryptionKeyPair;

    public OpenOffer(Offer offer, KeyPair signatureKeyPair, KeyPair encryptionKeyPair) {
        this.offer = offer;
        this.signatureKeyPair = signatureKeyPair;
        this.encryptionKeyPair = encryptionKeyPair;
        state = State.AVAILABLE;
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // PROTO BUFFER
    ///////////////////////////////////////////////////////////////////////////////////////////

    private OpenOffer(Offer offer,
                      @Nullable KeyPair signatureKeyPair,
                      @Nullable KeyPair encryptionKeyPair,
                      State state,
                      @Nullable NodeAddress arbitratorNodeAddress,
                      @Nullable NodeAddress mediatorNodeAddress,
                      @Nullable NodeAddress refundAgentNodeAddress) {
        this.offer = offer;
        this.signatureKeyPair = signatureKeyPair;
        this.encryptionKeyPair = encryptionKeyPair;
        this.state = state;
        this.arbitratorNodeAddress = arbitratorNodeAddress;
        this.mediatorNodeAddress = mediatorNodeAddress;
        this.refundAgentNodeAddress = refundAgentNodeAddress;

        if (this.state == State.RESERVED)
            setState(State.AVAILABLE);
    }

    @Override
    public protobuf.Tradable toProtoMessage() {
        protobuf.OpenOffer.Builder builder = protobuf.OpenOffer.newBuilder()
                .setOffer(offer.toProtoMessage())
                .setState(protobuf.OpenOffer.State.valueOf(state.name()));

        Optional.ofNullable(arbitratorNodeAddress).ifPresent(nodeAddress -> builder.setArbitratorNodeAddress(nodeAddress.toProtoMessage()));
        Optional.ofNullable(mediatorNodeAddress).ifPresent(nodeAddress -> builder.setMediatorNodeAddress(nodeAddress.toProtoMessage()));
        Optional.ofNullable(refundAgentNodeAddress).ifPresent(nodeAddress -> builder.setRefundAgentNodeAddress(nodeAddress.toProtoMessage()));

        Optional.ofNullable(signatureKeyPair).ifPresent(keyPair -> builder.setSigKey(ByteString.copyFrom(CryptoUtils.getEncodedPrivateKey(keyPair.getPrivate()))));
        Optional.ofNullable(encryptionKeyPair).ifPresent(keyPair -> builder.setEncrKey(ByteString.copyFrom(CryptoUtils.getEncodedPrivateKey(keyPair.getPrivate()))));

        return protobuf.Tradable.newBuilder().setOpenOffer(builder).build();
    }

    public static Tradable fromProto(protobuf.OpenOffer proto) {
        KeyPair signatureKeyPair = null;
        KeyPair encryptionKeyPair = null;
        try {
            PrivateKey signaturePrivateKey = CryptoUtils.getPrivateKeyFromEncodedKey(KeyStorage.KeyEntry.MSG_SIGNATURE, proto.getSigKey().toByteArray());
            PublicKey signaturePublicKey = Sig.getPublicSignatureKey(signaturePrivateKey);
            signatureKeyPair = new KeyPair(signaturePublicKey, signaturePrivateKey);

            PrivateKey encryptionPrivateKey = CryptoUtils.getPrivateKeyFromEncodedKey(KeyStorage.KeyEntry.MSG_ENCRYPTION, proto.getEncrKey().toByteArray());
            PublicKey encryptionPublicKey = Encryption.getPublicEncryptionKey(encryptionPrivateKey);
            encryptionKeyPair = new KeyPair(encryptionPublicKey, encryptionPrivateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            e.printStackTrace();
        }

        return new OpenOffer(Offer.fromProto(proto.getOffer()),
                signatureKeyPair,
                encryptionKeyPair,
                ProtoUtil.enumFromProto(OpenOffer.State.class, proto.getState().name()),
                proto.hasArbitratorNodeAddress() ? NodeAddress.fromProto(proto.getArbitratorNodeAddress()) : null,
                proto.hasMediatorNodeAddress() ? NodeAddress.fromProto(proto.getMediatorNodeAddress()) : null,
                proto.hasRefundAgentNodeAddress() ? NodeAddress.fromProto(proto.getRefundAgentNodeAddress()) : null);
    }

    public static KeyPair getKey(KeyStorage.KeyEntry keyEntry, byte[] privKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance(keyEntry.getAlgorithm());
            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privKey);
            PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
            PublicKey publicKey;

            if (privateKey instanceof RSAPrivateCrtKey) {
                // enc
                RSAPrivateCrtKey rsaPrivateKey = (RSAPrivateCrtKey) privateKey;
                RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(rsaPrivateKey.getModulus(), rsaPrivateKey.getPublicExponent());
                publicKey = keyFactory.generatePublic(publicKeySpec);
            } else if (privateKey instanceof DSAPrivateKey) {
                //sig
                DSAPrivateKey dsaPrivateKey = (DSAPrivateKey) privateKey;
                DSAParams dsaParams = dsaPrivateKey.getParams();
                BigInteger p = dsaParams.getP();
                BigInteger q = dsaParams.getQ();
                BigInteger g = dsaParams.getG();
                BigInteger y = g.modPow(dsaPrivateKey.getX(), p);
                KeySpec publicKeySpec = new DSAPublicKeySpec(y, p, q, g);
                publicKey = keyFactory.generatePublic(publicKeySpec);
            } else {
                throw new RuntimeException("Unsupported key algo" + keyEntry.getAlgorithm());
            }

            log.debug("load completed in {} msec", System.currentTimeMillis() - new Date().getTime());
            return new KeyPair(publicKey, privateKey);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            log.error("Could not load key " + keyEntry.toString(), e);
            throw new RuntimeException("Could not load key " + keyEntry.toString(), e);
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////
    // Getters
    ///////////////////////////////////////////////////////////////////////////////////////////

    public Optional<PubKeyRing> getPubKeyRing() {
        if (signatureKeyPair != null && encryptionKeyPair != null) {
            return Optional.of(new PubKeyRing(signatureKeyPair.getPublic(), encryptionKeyPair.getPublic()));
        } else {
            return Optional.empty();
        }
    }

    @Override
    public Date getDate() {
        return offer.getDate();
    }

    @Override
    public String getId() {
        return offer.getId();
    }

    @Override
    public String getShortId() {
        return offer.getShortId();
    }

    public void setState(State state) {
        this.state = state;

        // We keep it reserved for a limited time, if trade preparation fails we revert to available state
        if (this.state == State.RESERVED) {
            startTimeout();
        } else {
            stopTimeout();
        }
    }

    public boolean isDeactivated() {
        return state == State.DEACTIVATED;
    }

    private void startTimeout() {
        stopTimeout();

        timeoutTimer = UserThread.runAfter(() -> {
            log.debug("Timeout for resetting State.RESERVED reached");
            if (state == State.RESERVED) {
                // we do not need to persist that as at startup any RESERVED state would be reset to AVAILABLE anyway
                setState(State.AVAILABLE);
            }
        }, TIMEOUT);
    }

    private void stopTimeout() {
        if (timeoutTimer != null) {
            timeoutTimer.stop();
            timeoutTimer = null;
        }
    }


    @Override
    public String toString() {
        return "OpenOffer{" +
                ",\n     offer=" + offer +
                ",\n     state=" + state +
                ",\n     arbitratorNodeAddress=" + arbitratorNodeAddress +
                ",\n     mediatorNodeAddress=" + mediatorNodeAddress +
                ",\n     refundAgentNodeAddress=" + refundAgentNodeAddress +
                "\n}";
    }
}

