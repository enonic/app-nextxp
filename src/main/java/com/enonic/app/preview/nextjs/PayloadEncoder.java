package com.enonic.app.preview.nextjs;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;

public final class PayloadEncoder
    implements ScriptBean
{
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private static final int IV_LENGTH = 12;

    private static final int TAG_LENGTH_BITS = 128;

    public String encode( final String payload, final String secret )
    {
        try
        {
            final byte[] key = deriveKey( secret );
            final byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes( iv );

            final Cipher cipher = Cipher.getInstance( ALGORITHM );
            cipher.init( Cipher.ENCRYPT_MODE, new SecretKeySpec( key, "AES" ), new GCMParameterSpec( TAG_LENGTH_BITS, iv ) );

            final byte[] cipherText = cipher.doFinal( payload.getBytes( StandardCharsets.UTF_8 ) );

            // IV + ciphertext (includes auth tag appended by GCM)
            final byte[] result = new byte[IV_LENGTH + cipherText.length];
            System.arraycopy( iv, 0, result, 0, IV_LENGTH );
            System.arraycopy( cipherText, 0, result, IV_LENGTH, cipherText.length );

            return Base64.getUrlEncoder().withoutPadding().encodeToString( result );
        }
        catch ( GeneralSecurityException e )
        {
            throw new RuntimeException( "Failed to encrypt payload", e );
        }
    }

    public String decode( final String blob, final String secret )
    {
        try
        {
            final byte[] data = Base64.getUrlDecoder().decode( blob );
            final byte[] key = deriveKey( secret );

            final byte[] iv = new byte[IV_LENGTH];
            System.arraycopy( data, 0, iv, 0, IV_LENGTH );

            final Cipher cipher = Cipher.getInstance( ALGORITHM );
            cipher.init( Cipher.DECRYPT_MODE, new SecretKeySpec( key, "AES" ), new GCMParameterSpec( TAG_LENGTH_BITS, iv ) );

            final byte[] plainText = cipher.doFinal( data, IV_LENGTH, data.length - IV_LENGTH );

            return new String( plainText, StandardCharsets.UTF_8 );
        }
        catch ( GeneralSecurityException e )
        {
            throw new RuntimeException( "Failed to decrypt payload", e );
        }
    }

    private static byte[] deriveKey( final String secret )
    {
        try
        {
            return MessageDigest.getInstance( "SHA-256" ).digest( secret.getBytes( StandardCharsets.UTF_8 ) );
        }
        catch ( Exception e )
        {
            throw new RuntimeException( "SHA-256 not available", e );
        }
    }

    @Override
    public void initialize( final BeanContext context )
    {
        // No services needed
    }
}
