package com.enonic.app.preview.nextjs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class PayloadEncoderTest
{
    private PayloadEncoder encoder;

    @BeforeEach
    void setUp()
    {
        encoder = new PayloadEncoder();
    }

    @Test
    public void testRoundTrip()
    {
        final String payload =
            "{\"jsessionid\":\"abc123\",\"mode\":\"edit\",\"project\":\"myproject\",\"xpBaseUrl\":\"http://localhost:8080\"}";
        final String secret = "my-secret-key";

        final String encrypted = encoder.encode( payload, secret );
        final String decrypted = encoder.decode( encrypted, secret );

        assertEquals( payload, decrypted );
    }

    @Test
    public void testDifferentIVsProduceDifferentBlobs()
    {
        final String payload = "test";
        final String secret = "secret";

        final String blob1 = encoder.encode( payload, secret );
        final String blob2 = encoder.encode( payload, secret );

        assertNotEquals( blob1, blob2 );
        assertEquals( payload, encoder.decode( blob1, secret ) );
        assertEquals( payload, encoder.decode( blob2, secret ) );
    }

    @Test
    public void testWrongSecretFails()
    {
        final String payload = "sensitive data";
        final String encrypted = encoder.encode( payload, "correct-secret" );

        assertThrows( RuntimeException.class, () -> encoder.decode( encrypted, "wrong-secret" ) );
    }

    @Test
    public void testTamperedBlobFails()
    {
        final String encrypted = encoder.encode( "data", "secret" );
        final String tampered = encrypted.substring( 0, encrypted.length() - 2 ) + "XX";

        assertThrows( RuntimeException.class, () -> encoder.decode( tampered, "secret" ) );
    }
}
