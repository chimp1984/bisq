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

package bisq.common.crypto;

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import java.util.Base64;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CryptoUtils {
    public static String pubKeyToString(PublicKey publicKey) {
        final X509EncodedKeySpec x509EncodedKeySpec = new X509EncodedKeySpec(publicKey.getEncoded());
        return Base64.getEncoder().encodeToString(x509EncodedKeySpec.getEncoded());
    }

    public static byte[] getRandomBytes(int size) {
        byte[] bytes = new byte[size];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }

    public static PrivateKey getPrivateKeyFromEncodedKey(KeyStorage.KeyEntry keyEntry, byte[] privKey)
            throws NoSuchAlgorithmException, InvalidKeySpecException {
        KeyFactory keyFactory = KeyFactory.getInstance(keyEntry.getAlgorithm());
        PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privKey);
        return keyFactory.generatePrivate(privateKeySpec);
    }

    public static byte[] getEncodedPrivateKey(PrivateKey privateKey) {
        return new PKCS8EncodedKeySpec(privateKey.getEncoded()).getEncoded();
    }
}
