package com.example.backend.shortform.storage;

import com.example.backend.common.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MediaStorageTests {
    @TempDir Path root;
    private MediaStorage storage;
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @BeforeEach void setUp() { storage = new MediaStorage(root.toString()); }

    @Test void storesPhotoUsingServerKeyAndStreamsItBack() throws Exception {
        var media = storage.store(new MockMultipartFile("files", "../../secret\\photo.png", "image/png", PNG));
        assertEquals(MediaKind.PHOTO, media.kind());
        assertEquals("image/png", media.contentType());
        assertEquals("photo.png", media.originalFilename());
        assertEquals(PNG.length, media.sizeBytes());
        assertTrue(media.storageKey().matches("[a-f0-9-]{36}\\.png"));
        try (var input = storage.load(media.storageKey()).getInputStream()) {
            assertArrayEquals(PNG, input.readAllBytes());
        }
        storage.delete(media.storageKey());
        assertThrows(ApiException.class, () -> storage.load(media.storageKey()));
        storage.delete(media.storageKey());
    }

    @Test void recognizesSupportedVideoContainersAndWebp() {
        byte[] mp4 = {0,0,0,20,'f','t','y','p','i','s','o','m',0,0,0,0,'i','s','o','m'};
        var video = storage.store(new MockMultipartFile("files", "clip.mp4", "video/mp4", mp4));
        assertEquals(MediaKind.VIDEO, video.kind());
        assertEquals("video/mp4", video.contentType());
        byte[] mov = mp4.clone(); mov[8] = 'q'; mov[9] = 't'; mov[10] = ' '; mov[11] = ' ';
        assertEquals("video/quicktime", storage.store(new MockMultipartFile("files", "clip.mov", "video/quicktime", mov)).contentType());
        byte[] webm = {0x1a,0x45,(byte)0xdf,(byte)0xa3,0,0,'w','e','b','m'};
        assertEquals("video/webm", storage.store(new MockMultipartFile("files", "clip.webm", "video/webm", webm)).contentType());
        assertEquals("image/webp", storage.store(new MockMultipartFile("files", "photo.webp", "image/webp",
                new byte[]{'R','I','F','F',16,0,0,0,'W','E','B','P','V','P','8',' ',4,0,0,0,1,2,3,4})).contentType());
    }

    @Test void rejectsEmptyUnsupportedAndMismatchedMimeTypesWithoutCreatingFiles() throws Exception {
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", new byte[0])));
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", "evil.jpg", "image/jpeg", "<svg/>".getBytes())));
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", "photo.jpg", "image/jpeg", PNG)));
        byte[] heic = {0,0,0,16,'f','t','y','p','h','e','i','c',0,0,0,0};
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", "photo.heic", "video/mp4", heic)));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsTraversalAndSymlinkKeys() throws Exception {
        for (String key : new String[]{"../outside.jpg", "/tmp/photo.jpg", "C:\\Windows\\file.jpg", "not-a-uuid.jpg"}) {
            assertThrows(ApiException.class, () -> storage.load(key));
            assertThrows(ApiException.class, () -> storage.delete(key));
        }
    }

    @Test void rejectsSymbolicLinksWithoutTouchingTheirTargets() throws Exception {
        String symlinkKey = UUID.randomUUID() + ".png";
        Path outside = root.resolveSibling("outside-" + UUID.randomUUID() + ".png");
        Files.write(outside, PNG);
        try {
            try { Files.createSymbolicLink(root.resolve(symlinkKey), outside); }
            catch (java.nio.file.FileSystemException | UnsupportedOperationException exception) {
                org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic-link creation is unavailable: " + exception.getClass().getSimpleName());
            }
            assertThrows(ApiException.class, () -> storage.load(symlinkKey));
            assertThrows(ApiException.class, () -> storage.delete(symlinkKey));
            assertArrayEquals(PNG, Files.readAllBytes(outside));
        } finally { Files.deleteIfExists(root.resolve(symlinkKey)); Files.deleteIfExists(outside); }
    }

    @Test void rejectsTruncatedPhotoEnvelopesAndRemovesIncompleteFiles() throws Exception {
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", "photo.png", "image/png",
                java.util.Arrays.copyOf(PNG, PNG.length - 5))));
        assertThrows(ApiException.class, () -> storage.store(new MockMultipartFile("files", "photo.jpg", "image/jpeg",
                new byte[]{(byte)0xff,(byte)0xd8,(byte)0xff,(byte)0xe0,0,2})));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void acceptsJpegProducedByImageIo() throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        assertTrue(javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(1,1,java.awt.image.BufferedImage.TYPE_INT_RGB), "jpeg", bytes));
        var result = storage.store(new MockMultipartFile("files", "photo.jpg", "image/jpeg", bytes.toByteArray()));
        assertEquals("image/jpeg", result.contentType());
    }

    @Test void enforcesStreamedSizeEvenWhenDeclaredSizeIsWrongAndDeletesPartialFile() throws Exception {
        MockMultipartFile oversized = new MockMultipartFile("files", "large.png", "image/png", PNG) {
            @Override public long getSize() { return PNG.length; }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    long position;
                    @Override public int read() {
                        if (position >= MediaStorage.MAX_PHOTO_BYTES + 1) return -1;
                        return position < PNG.length ? Byte.toUnsignedInt(PNG[(int) position++]) : (++position > 0 ? 0 : -1);
                    }
                    @Override public int read(byte[] bytes, int offset, int length) {
                        if (position >= MediaStorage.MAX_PHOTO_BYTES + 1) return -1;
                        int count = (int) Math.min(length, MediaStorage.MAX_PHOTO_BYTES + 1 - position);
                        java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0);
                        if (position < PNG.length) {
                            int prefix = (int) Math.min(count, PNG.length - position);
                            System.arraycopy(PNG, (int) position, bytes, offset, prefix);
                        }
                        position += count;
                        return count;
                    }
                };
            }
        };
        var error = assertThrows(ApiException.class, () -> storage.store(oversized));
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, error.getStatus());
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsDeclaredOversizeWithoutOpeningInputStream() {
        var oversized = new MockMultipartFile("files", PNG) {
            @Override public long getSize() { return MediaStorage.MAX_VIDEO_BYTES + 1; }
            @Override public InputStream getInputStream() { throw new AssertionError("Must reject before reading"); }
        };
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(ApiException.class, () -> storage.store(oversized)).getStatus());
    }
}
