import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.Base64;

/** Generates disposable experiment keys without using development credentials. */
class TestKeys {
    public static void main(String[] args) throws Exception {
        var folder=Path.of(args[0]); Files.createDirectories(folder);
        var generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var pair=generator.generateKeyPair();
        Files.writeString(folder.resolve("private.pem"),"-----BEGIN PRIVATE KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())+"\n-----END PRIVATE KEY-----\n");
        Files.writeString(folder.resolve("public.pem"),"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");
    }
}
