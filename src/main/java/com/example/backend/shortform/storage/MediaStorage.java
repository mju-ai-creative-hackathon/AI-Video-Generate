package com.example.backend.shortform.storage;

import com.example.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private media files. Controllers must authorize ownership before load/delete. */
@Component
public class MediaStorage {
    private static final Logger LOG = LoggerFactory.getLogger(MediaStorage.class);
    public static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 100L * 1024 * 1024;
    private static final int HEADER_BYTES = 4096;
    private static final Pattern STORAGE_KEY = Pattern.compile(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(jpg|png|webp|mp4|mov|webm)");
    private static final Set<String> MP4_BRANDS = Set.of(
            "isom", "iso2", "iso3", "iso4", "iso5", "iso6", "mp41", "mp42", "avc1", "M4V ", "dash");
    private final Path root;

    public MediaStorage(@Value("${app.shortform.storage-root:./data/shortforms}") String storageRoot) {
        try {
            Path configured = Path.of(storageRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configured)) throw new IOException("Storage root is a symbolic link");
            Files.createDirectories(configured);
            this.root = configured.toRealPath();
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("미디어 저장소를 초기화할 수 없습니다.", exception);
        }
    }

    public StoredMedia store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("빈 미디어 파일은 업로드할 수 없습니다.");
        if (file.getSize() > MAX_VIDEO_BYTES) throw tooLarge();
        Path destination = null;
        boolean created = false;
        try (InputStream input = file.getInputStream()) {
            byte[] header = input.readNBytes(HEADER_BYTES);
            Format format = detect(header);
            validateDeclaredType(file.getContentType(), format.contentType());
            long maximum = format.kind() == MediaKind.PHOTO ? MAX_PHOTO_BYTES : MAX_VIDEO_BYTES;
            if (file.getSize() > maximum || header.length > maximum) throw tooLarge();
            String key = UUID.randomUUID() + "." + format.extension();
            destination = resolve(key);
            long total = header.length;
            byte[] tail = new byte[12];
            updateTail(tail, header, header.length);
            try (OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                created = true;
                output.write(header);
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > maximum) throw tooLarge();
                    output.write(buffer, 0, read);
                    updateTail(tail, buffer, read);
                }
            }
            validatePhotoEnvelope(format, header, tail, total);
            return new StoredMedia(key, safeFilename(file.getOriginalFilename(), format.extension()),
                    format.contentType(), total, format.kind());
        } catch (ApiException exception) {
            cleanPartial(created ? destination : null);
            throw exception;
        } catch (IOException exception) {
            cleanPartial(created ? destination : null);
            throw storageError();
        }
    }

    public Resource load(String storageKey) {
        Path file = resolve(storageKey);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw ApiException.notFound("미디어 파일을 찾을 수 없습니다.");
        return new FileSystemResource(file);
    }

    public void delete(String storageKey) {
        Path file = resolve(storageKey);
        try {
            if (Files.isSymbolicLink(file)) throw ApiException.badRequest("유효하지 않은 미디어 경로입니다.");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw ApiException.badRequest("유효하지 않은 미디어 경로입니다.");
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            throw storageError();
        }
    }

    private Path resolve(String key) {
        if (key == null || !STORAGE_KEY.matcher(key).matches())
            throw ApiException.badRequest("유효하지 않은 미디어 저장소 키입니다.");
        Path target = root.resolve(key).normalize();
        if (!target.getParent().equals(root) || Files.isSymbolicLink(target))
            throw ApiException.badRequest("유효하지 않은 미디어 경로입니다.");
        return target;
    }

    private static Format detect(byte[] bytes) {
        if (bytes.length >= 4 && unsigned(bytes[0]) == 0xff && unsigned(bytes[1]) == 0xd8
                && unsigned(bytes[2]) == 0xff && unsigned(bytes[3]) >= 0xc0
                && unsigned(bytes[3]) != 0xd8 && unsigned(bytes[3]) != 0xd9 && unsigned(bytes[3]) != 0xff)
            return new Format("jpg", "image/jpeg", MediaKind.PHOTO);
        if (bytes.length >= 33 && unsigned(bytes[0]) == 0x89 && ascii(bytes, 1, 3).equals("PNG")
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10
                && ByteBuffer.wrap(bytes, 8, 4).getInt() == 13 && ascii(bytes, 12, 4).equals("IHDR")
                && ByteBuffer.wrap(bytes, 16, 4).getInt() > 0 && ByteBuffer.wrap(bytes, 20, 4).getInt() > 0)
            return new Format("png", "image/png", MediaKind.PHOTO);
        if (bytes.length >= 20 && ascii(bytes, 0, 4).equals("RIFF") && ascii(bytes, 8, 4).equals("WEBP")
                && Set.of("VP8 ", "VP8L", "VP8X").contains(ascii(bytes, 12, 4)))
            return new Format("webp", "image/webp", MediaKind.PHOTO);
        if (bytes.length >= 16 && ascii(bytes, 4, 4).equals("ftyp")) {
            int atomSize = ByteBuffer.wrap(bytes, 0, 4).getInt();
            if (atomSize < 16 || atomSize > bytes.length) throw unsupported();
            String brand = ascii(bytes, 8, 4);
            if (brand.equals("qt  ")) return new Format("mov", "video/quicktime", MediaKind.VIDEO);
            if (MP4_BRANDS.contains(brand)) return new Format("mp4", "video/mp4", MediaKind.VIDEO);
        }
        if (bytes.length >= 8 && unsigned(bytes[0]) == 0x1a && unsigned(bytes[1]) == 0x45
                && unsigned(bytes[2]) == 0xdf && unsigned(bytes[3]) == 0xa3
                && new String(bytes, StandardCharsets.ISO_8859_1).contains("webm"))
            return new Format("webm", "video/webm", MediaKind.VIDEO);
        throw unsupported();
    }

    // This checks file envelopes and truncation, not codec-level decodability.
    private static void validatePhotoEnvelope(Format format, byte[] header, byte[] tail, long total) {
        if (format.extension().equals("jpg")
                && (total < 6 || unsigned(tail[10]) != 0xff || unsigned(tail[11]) != 0xd9)) throw unsupported();
        if (format.extension().equals("png")
                && !java.util.Arrays.equals(tail, new byte[]{0,0,0,0,'I','E','N','D',(byte)0xae,0x42,0x60,(byte)0x82}))
            throw unsupported();
        if (format.extension().equals("webp")) {
            long riffLength = Integer.toUnsignedLong(ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            long chunkLength = Integer.toUnsignedLong(ByteBuffer.wrap(header, 16, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            if (riffLength + 8 != total || chunkLength == 0 || chunkLength + 20 > total) throw unsupported();
        }
    }

    private static void updateTail(byte[] tail, byte[] bytes, int count) {
        int copy = Math.min(count, tail.length);
        if (copy < tail.length) System.arraycopy(tail, copy, tail, 0, tail.length - copy);
        System.arraycopy(bytes, count - copy, tail, tail.length - copy, copy);
    }

    private static void validateDeclaredType(String declared, String detected) {
        if (declared == null || declared.isBlank()) return;
        try {
            MediaType contentType = MediaType.parseMediaType(declared);
            String normalized = contentType.getType().toLowerCase(Locale.ROOT) + "/"
                    + contentType.getSubtype().toLowerCase(Locale.ROOT);
            if (!normalized.equals(MediaType.APPLICATION_OCTET_STREAM_VALUE) && !normalized.equals(detected))
                throw ApiException.badRequest("파일 내용과 미디어 유형이 일치하지 않습니다.");
        } catch (IllegalArgumentException exception) {
            throw ApiException.badRequest("유효하지 않은 미디어 유형입니다.");
        }
    }

    private static String safeFilename(String original, String extension) {
        if (original == null || original.isBlank()) return "upload." + extension;
        String normalized = original.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}]", "").strip();
        if (normalized.isEmpty() || normalized.equals(".") || normalized.equals("..")) return "upload." + extension;
        return normalized.length() > 255 ? normalized.substring(0, 255) : normalized;
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.US_ASCII);
    }
    private static int unsigned(byte value) { return Byte.toUnsignedInt(value); }
    private static ApiException unsupported() {
        return ApiException.badRequest("JPEG, PNG, WebP 사진 또는 MP4, MOV, WebM 영상만 업로드할 수 있습니다.");
    }
    private static ApiException tooLarge() {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "MEDIA_TOO_LARGE", "사진은 10MiB, 영상은 100MiB 이하여야 합니다.");
    }
    private static ApiException storageError() {
        return new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "MEDIA_STORAGE_ERROR", "미디어 저장소 처리에 실패했습니다.");
    }
    private static void cleanPartial(Path file) {
        if (file == null) return;
        try { Files.deleteIfExists(file); }
        catch (IOException ignored) { LOG.warn("Could not remove incomplete media file {}", file.getFileName()); }
    }
    private record Format(String extension, String contentType, MediaKind kind) {}
}
