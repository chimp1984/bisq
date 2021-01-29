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

import bisq.network.p2p.NodeAddress;
import bisq.network.p2p.storage.payload.ExpirablePayload;
import bisq.network.p2p.storage.payload.ProtectedStoragePayload;
import bisq.network.p2p.storage.payload.RequiresOwnerIsOnlinePayload;

import com.google.protobuf.Message;

import java.security.PublicKey;

import java.util.Map;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.jetbrains.annotations.Nullable;

// OfferPayload has about 1.4 kb. We should look into options to make it smaller but will be hard to do it in a
// backward compatible way. Maybe a candidate when segwit activation is done as hardfork?

@EqualsAndHashCode
@Getter
@Slf4j
public final class AtomicSwapOfferPayload implements ProtectedStoragePayload, ExpirablePayload, RequiresOwnerIsOnlinePayload {

    @Override
    public long getTTL() {
        return 0;
    }

    @Override
    public PublicKey getOwnerPubKey() {
        return null;
    }

    @Nullable
    @Override
    public Map<String, String> getExtraDataMap() {
        return null;
    }

    @Override
    public NodeAddress getOwnerNodeAddress() {
        return null;
    }

    @Override
    public Message toProtoMessage() {
        return null;
    }
}
