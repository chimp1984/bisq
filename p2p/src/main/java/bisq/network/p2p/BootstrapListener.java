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

package bisq.network.p2p;

// For clients which are only interested in the state once the p2p network is bootstrapped,
// which is when the onUpdatedDataReceived or onNoSeedNodeAvailable was called.
public abstract class BootstrapListener implements P2PServiceListener {
    public abstract void onBootstrapped();

    @Override
    public void onNoSeedNodeAvailable() {
    }

    @Override
    public void onUpdatedDataReceived() {
    }

    @Override
    public void onTorNodeReady() {
    }

    @Override
    public void onHiddenServicePublished() {
    }

    @Override
    public void onNoPeersAvailable() {
    }

    @Override
    public void onDataReceived() {
    }
}

