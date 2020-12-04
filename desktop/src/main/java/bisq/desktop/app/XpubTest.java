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

package bisq.desktop.app;

import bisq.common.app.Log;

import org.bitcoinj.core.Address;
import org.bitcoinj.crypto.ChildNumber;
import org.bitcoinj.crypto.DeterministicKey;
import org.bitcoinj.params.MainNetParams;
import org.bitcoinj.script.Script;
import org.bitcoinj.wallet.DeterministicKeyChain;
import org.bitcoinj.wallet.KeyChain;

import com.google.common.collect.ImmutableList;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import ch.qos.logback.classic.Level;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class XpubTest {
    public static final ImmutableList<ChildNumber> BIP44_BTC_NON_SEGWIT_ACCOUNT_PATH = ImmutableList.of(
            new ChildNumber(44, true),
            new ChildNumber(0, true),
            ChildNumber.ZERO_HARDENED);

    public static final ImmutableList<ChildNumber> BIP44_BSQ_NON_SEGWIT_ACCOUNT_PATH = ImmutableList.of(
            new ChildNumber(44, true),
            new ChildNumber(142, true),
            ChildNumber.ZERO_HARDENED);
    private static int ITERATIONS;

    public static void main(String[] args) throws IOException {
        Log.setup("XpubTest");
        Log.setLevel(Level.WARN);

        ITERATIONS = 1000;
        boolean writeFile = false;
        // example keys from a dev app....
        String bsqXpub = "xpub6CxQT7PZvVRxyBDXv3ZpVBTeuHr6ib5AWtYaJWzMcJiH2Tvfo2gNusa492JfBgmWfTqBBeBoeeXLZdmhWGy8U4MgnsQ9Pq8esM1BRZVXzh7";
        String btcLegacyXpub = "xpub6ChYnvHx3vJyraf5mStmLFb2QojdTmLiDfe7c38dWYnhB4uS8eyTFGzBjaT8G4c5MF33uYwzQiiRB9hYndHY23yHCWjHBhm6CYM2YJSGGwe";
        String btcSegwitXpub = "zpub6rN5QFdnMHPwasHmchNsteySiXa11fikmZBwRvcjFVqCZTMhk39pHNvUD8kFcQuxG8b9J2Maz4mm3dtkx9gnfxxb8phKsiUxFQoYvVLtHPc";

        StringBuilder sb = new StringBuilder();
        generate(bsqXpub, "BSQ", sb, Script.ScriptType.P2PKH);
        generate(btcLegacyXpub, "BTC Legacy", sb, Script.ScriptType.P2PKH);
        generate(btcSegwitXpub, "BTC Segwit", sb, Script.ScriptType.P2WPKH);
        log.warn(sb.toString());

        if (writeFile) {
            FileWriter fw = new FileWriter("all-addresses-from-xPub.txt");
            PrintWriter pw = new PrintWriter(fw);
            pw.println(sb.toString());
            pw.close();
        }
    }

    protected static void generate(String xPub,
                                   String info,
                                   StringBuilder sb,
                                   Script.ScriptType scriptType) {
        DeterministicKey watchKey = DeterministicKey.deserializeB58(null, xPub, MainNetParams.get());
        watchKey.setCreationTimeSeconds(0);

        DeterministicKeyChain chain = DeterministicKeyChain.builder().watch(watchKey).outputScriptType(scriptType)
                .build();
        sb.append("\n").append("#################################################\n");
        sb.append(info).append("\n");
        sb.append("#################################################\n");
        sb.append("xPub = ").append(xPub).append("\n");
        sb.append("watchKey = ").append(watchKey).append("\n");
        sb.append("chain = ").append(chain).append("\n");
        iterate(KeyChain.KeyPurpose.RECEIVE_FUNDS, sb, scriptType, chain);
        iterate(KeyChain.KeyPurpose.CHANGE, sb, scriptType, chain);
        sb.append("\n");
        sb.append("\n");
    }

    private static void iterate(KeyChain.KeyPurpose keyPurpose,
                                StringBuilder sb,
                                Script.ScriptType scriptType,
                                DeterministicKeyChain chain) {
        sb.append("\n").append(keyPurpose.name()).append("\n");
        for (int i = 0; i < ITERATIONS; i++) {
            DeterministicKey key = chain.getKey(keyPurpose);
            Address address = Address.fromKey(MainNetParams.get(), key, scriptType);
            sb.append("address = ").append(address).append("\n");
        }
    }
}


