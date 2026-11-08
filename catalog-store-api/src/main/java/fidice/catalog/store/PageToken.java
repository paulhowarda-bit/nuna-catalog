package fidice.catalog.store;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Encodes the sort key of a page's last item as an opaque token, and decodes it. A listing
 * resumes strictly after that key (keyset paging), so rows inserted meanwhile never shift it.
 */
public final class PageToken {

    private PageToken() {
    }

    public static String encode(String... parts) {
        List<byte[]> encoded = new ArrayList<>();
        int size = 0;
        for (String part : parts) {
            byte[] bytes = part.getBytes(UTF_8);
            encoded.add(bytes);
            size += Integer.BYTES + bytes.length;
        }
        ByteBuffer buffer = ByteBuffer.allocate(size);
        for (byte[] bytes : encoded) {
            buffer.putInt(bytes.length).put(bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer.array());
    }

    /** The {@code count} parts of {@code token}; a token that is not one of ours is an IllegalArgumentException. */
    public static List<String> decode(String token, int count) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getUrlDecoder().decode(token));
            List<String> parts = new ArrayList<>();
            while (buffer.hasRemaining()) {
                byte[] bytes = new byte[buffer.getInt()];
                buffer.get(bytes);
                parts.add(new String(bytes, UTF_8));
            }
            if (parts.size() != count) {
                throw new IllegalArgumentException("page token has " + parts.size() + " parts, not " + count);
            }
            return parts;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not a page token: " + token, e);
        }
    }
}
