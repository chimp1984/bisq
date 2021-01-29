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

import bisq.common.proto.network.NetworkPayload;
import bisq.common.proto.persistable.PersistablePayload;

import com.google.protobuf.Message;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

// OfferPayload has about 1.4 kb. We should look into options to make it smaller but will be hard to do it in a
// backward compatible way. Maybe a candidate when segwit activation is done as hardfork?

@EqualsAndHashCode
@Getter
@Slf4j
public final class AtomicSwapOffer implements NetworkPayload, PersistablePayload, OfferBookEntry {
    public AtomicSwapOffer(AtomicSwapOfferPayload offerPayload) {

    }

    @Override
    public Message toProtoMessage() {
        return null;
    }
}
