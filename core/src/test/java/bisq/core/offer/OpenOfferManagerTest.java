package bisq.core.offer;

import bisq.network.p2p.P2PService;
import bisq.network.p2p.peers.PeerManager;

import bisq.common.file.CorruptedStorageFileHandler;
import bisq.common.handlers.ErrorMessageHandler;
import bisq.common.handlers.ResultHandler;
import bisq.common.persistence.PersistenceManager;

import java.nio.file.Files;

import java.io.File;
import java.io.IOException;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Before;
import org.junit.Test;

import static bisq.core.offer.OfferMaker.btcUsdOffer;
import static com.natpryce.makeiteasy.MakeItEasy.make;
import static junit.framework.TestCase.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class OpenOfferManagerTest {

    private CorruptedStorageFileHandler corruptedStorageFileHandler;
    private File storageDir;

    @Before
    public void setUp() throws Exception {
        corruptedStorageFileHandler = mock(CorruptedStorageFileHandler.class);
        storageDir = Files.createTempDirectory("storage").toFile();
    }

    @Test
    public void testStartEditOfferForActiveOffer() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);

        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(null, null, null, p2PService,
                null, null, null, offerBookService,
                null, null, null,
                null, null, null, null, null, null,
                new PersistenceManager<>(storageDir, null, corruptedStorageFileHandler));

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);


        doAnswer(invocation -> {
            ((ResultHandler) invocation.getArgument(1)).handleResult();
            return null;
        }).when(offerBookService).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer), signatureKeyPair, encryptionKeyPair);

        ResultHandler resultHandler = () -> {
            startEditOfferSuccessful.set(true);
        };

        manager.editOpenOfferStart(openOffer, resultHandler, null);

        verify(offerBookService, times(1)).deactivateOffer(any(OfferPayload.class), any(ResultHandler.class), any(ErrorMessageHandler.class));

        assertTrue(startEditOfferSuccessful.get());

    }

    @Test
    public void testStartEditOfferForDeactivatedOffer() throws IOException {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);
        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(null, null, null, p2PService,
                null, null, null, offerBookService,
                null, null, null,
                null, null, null, null, null, null,
                new PersistenceManager<>(storageDir, null, corruptedStorageFileHandler));

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);

        ResultHandler resultHandler = () -> {
            startEditOfferSuccessful.set(true);
        };

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer), signatureKeyPair, encryptionKeyPair);
        openOffer.setState(OpenOffer.State.DEACTIVATED);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());

    }

    @Test
    public void testStartEditOfferForOfferThatIsCurrentlyEdited() {
        P2PService p2PService = mock(P2PService.class);
        OfferBookService offerBookService = mock(OfferBookService.class);

        when(p2PService.getPeerManager()).thenReturn(mock(PeerManager.class));

        final OpenOfferManager manager = new OpenOfferManager(null, null, null, p2PService,
                null, null, null, offerBookService,
                null, null, null,
                null, null, null, null, null, null,
                new PersistenceManager<>(storageDir, null, corruptedStorageFileHandler));

        AtomicBoolean startEditOfferSuccessful = new AtomicBoolean(false);

        ResultHandler resultHandler = () -> {
            startEditOfferSuccessful.set(true);
        };

        final OpenOffer openOffer = new OpenOffer(make(btcUsdOffer), signatureKeyPair, encryptionKeyPair);
        openOffer.setState(OpenOffer.State.DEACTIVATED);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());

        startEditOfferSuccessful.set(false);

        manager.editOpenOfferStart(openOffer, resultHandler, null);
        assertTrue(startEditOfferSuccessful.get());
    }

}
