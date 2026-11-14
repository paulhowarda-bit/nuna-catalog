import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The demo's stand-in identity provider, JDK only: an RS256 signing key, its JWKS, and a bearer token per demo
 * user, valid for seven days. The catalog fetches the JWKS by URL: demo.sh serves it from Service idp (as the
 * engine matrix does), deploy/local/run.sh from Silo.
 *
 * <pre>java Mint.java &lt;run-dir&gt;</pre>
 *
 * Writes {@code idp/keys.json}, keeps the private key in {@code idp/signing-key.pk8} (made once, reused), and
 * writes one token per user to {@code tokens/<user>.jwt}. Test key, test tokens: never use them anywhere else.
 */
public final class Mint {

    static final String ISSUER = "https://idp.demo/keel";
    static final String AUDIENCE = "keel-catalog";
    static final String KEY_ID = "keel-demo-1";
    static final Duration LIFETIME = Duration.ofDays(7);

    /** user -> {client id, groups}. */
    static final Map<String, String[]> USERS = new LinkedHashMap<>();

    static {
        USERS.put("ana", new String[] {"keel-console", "demo-analysts"});
        USERS.put("gary", new String[] {"keel-console", "demo-grant-admins"});
        USERS.put("stella", new String[] {"keel-console", "demo-stewards"});
        USERS.put("oscar", new String[] {"keel-console", "demo-operators"});
        USERS.put("singlestore", new String[] {"singlestore-demo", "demo-loaders"});
        USERS.put("lake", new String[] {"lake-writer-demo", "demo-loaders"});
        // Kafka Connect's Iceberg sinks in the CDC leg (demo.sh cdc).
        USERS.put("cdc", new String[] {"kafka-connect-demo", "demo-loaders"});
        // The engine matrix's engines, run against the demo's stack by demo.sh engines.
        USERS.put("engine-matrix", new String[] {"engine-matrix", "engine-clients"});
    }

    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        Path idp = Files.createDirectories(run.resolve("idp"));
        Path tokens = Files.createDirectories(run.resolve("tokens"));
        Path keyFile = idp.resolve("signing-key.pk8");
        RSAPrivateCrtKey key;
        if (Files.exists(keyFile)) {
            PrivateKey stored = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(keyFile)));
            key = (RSAPrivateCrtKey) stored;
        } else {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            key = (RSAPrivateCrtKey) pair.getPrivate();
            Files.write(keyFile, key.getEncoded());
        }
        Files.writeString(idp.resolve("keys.json"), "{\"keys\":[{\"kty\":\"RSA\",\"use\":\"sig\",\"alg\":\"RS256\",\"kid\":\""
                + KEY_ID + "\",\"n\":\"" + b64(unsigned(key.getModulus())) + "\",\"e\":\"" + b64(unsigned(key.getPublicExponent()))
                + "\"}]}\n");
        Instant now = Instant.now();
        long iat = now.minusSeconds(60).getEpochSecond();
        long exp = now.plus(LIFETIME).getEpochSecond();
        for (Map.Entry<String, String[]> user : USERS.entrySet()) {
            String header = "{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + KEY_ID + "\"}";
            String claims = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + AUDIENCE + "\",\"sub\":\"" + user.getKey()
                    + "\",\"azp\":\"" + user.getValue()[0] + "\",\"groups\":[\"" + user.getValue()[1] + "\"],\"iat\":" + iat
                    + ",\"nbf\":" + iat + ",\"exp\":" + exp + ",\"jti\":\"" + UUID.randomUUID() + "\"}";
            String signingInput = b64(header.getBytes(StandardCharsets.UTF_8)) + "." + b64(claims.getBytes(StandardCharsets.UTF_8));
            Signature rs256 = Signature.getInstance("SHA256withRSA");
            rs256.initSign(key);
            rs256.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            Files.writeString(tokens.resolve(user.getKey() + ".jwt"), signingInput + "." + b64(rs256.sign()) + "\n");
        }
        Files.writeString(tokens.resolve("expires.txt"), Instant.ofEpochSecond(exp) + "\n");
        System.out.println("minted " + USERS.size() + " tokens, valid until " + Instant.ofEpochSecond(exp));
    }

    private static byte[] unsigned(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
