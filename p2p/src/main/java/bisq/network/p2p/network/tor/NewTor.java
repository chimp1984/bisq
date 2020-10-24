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

package bisq.network.p2p.network.tor;

import org.berndpruenster.netlayer.tor.NativeTor;
import org.berndpruenster.netlayer.tor.Tor;
import org.berndpruenster.netlayer.tor.TorCtlException;
import org.berndpruenster.netlayer.tor.Torrc;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;

import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * This class creates a brand new instance of the Tor onion router.
 *
 * When asked, the class checks, whether command line parameters such as
 * --torrcFile and --torrcOptions are set and if so, takes these settings into
 * account. Then, a fresh set of Tor binaries is installed and Tor is launched.
 * Finally, a {@link Tor} instance is returned for further use.
 *
 * @author Florian Reimair
 *
 */
@Slf4j
public class NewTor extends TorMode {
    @Nullable
    private final File torrcFile;
    private final String torrcOptions;
    private final Collection<String> bridgeEntries;

    public NewTor(File torWorkingDirectory,
                  @Nullable File torrcFile,
                  String torrcOptions,
                  Collection<String> bridgeEntries) {
        super(torWorkingDirectory);

        this.torrcFile = torrcFile;
        this.torrcOptions = torrcOptions;
        this.bridgeEntries = bridgeEntries;
    }

    @Override
    public Tor getTor() throws IOException, TorCtlException {
        long ts = new Date().getTime();

        if (bridgeEntries != null) {
            log.info("Using bridges: {}", String.join(",", bridgeEntries));
        }

        Torrc torrc = null;

        // check if the user wants to provide his own torrc file
        if (torrcFile != null) {
            try {
                torrc = new Torrc(new FileInputStream(torrcFile));
            } catch (IOException e) {
                log.error("custom torrc file not found ('{}'). Proceeding with defaults.", torrcFile);
            }
        }

        // check if the user wants to temporarily add to the default torrc file
        LinkedHashMap<String, String> torrcOptionsMap = new LinkedHashMap<>();
        if (!"".equals(torrcOptions)) {
            Arrays.asList(torrcOptions.split(",")).forEach(line -> {
                line = line.trim();
                if (line.matches("^[^\\s]+\\s.+")) {
                    String[] tmp = line.split("\\s", 2);
                    torrcOptionsMap.put(tmp[0].trim(), tmp[1].trim());
                } else {
                    log.error("custom torrc parse error ('{}'). Proceeding without custom overrides.", line);
                    torrcOptionsMap.clear();
                }
            });
        }

        // assemble final torrc options
        if (!torrcOptionsMap.isEmpty()) {
            // check for custom torrcFile
            if (torrc != null) {
                // and merge the contents
                torrc = new Torrc(torrc.getInputStream$tor_native(), torrcOptionsMap);
            } else {
                torrc = new Torrc(torrcOptionsMap);
            }
        }
        log.info("Starting tor");
        NativeTor tor = new NativeTor(torDir, bridgeEntries, torrc);
        log.info(
                "\n################################################################\n" +
                        "Tor started after {} ms. Start publishing hidden service.\n" +
                        "################################################################",
                (new Date().getTime() - ts));

        return tor;
    }

    // TODO is that correct?
    @Override
    public String getHiddenServiceDirectory() {
        return "";
    }
}
