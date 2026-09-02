package com.enonic.app.preview.nextjs;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enonic.xp.portal.PortalRequest;
import com.enonic.xp.portal.PortalResponse;
import com.enonic.xp.server.RunMode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PreviewCspProcessorTest
{
    private PreviewCspProcessor processor;

    private PortalRequest request;

    private PortalResponse response;

    @BeforeEach
    void setUp()
    {
        processor = new PreviewCspProcessor( RunMode.DEV );
        request = new PortalRequest();
        response = PortalResponse.create().build();
    }

    private List<String> directive( final String name )
    {
        return request.getContentSecurityPolicy().directive( name ).orElse( List.of() );
    }

    @Test
    public void testConfiguredOriginsAddedToFrameSrcAndConnectSrc()
    {
        processor.activate( Map.of( "nextjs.default.url", "https://site.example.com/some/path", //
                                    "nextjs.production.url", "http://localhost:3001" ) );

        processor.process( request, response );

        final List<String> frameSrc = directive( "frame-src" );
        final List<String> connectSrc = directive( "connect-src" );

        assertTrue( frameSrc.contains( "https://site.example.com" ) );
        assertTrue( frameSrc.contains( "http://localhost:3001" ) );
        assertTrue( connectSrc.contains( "https://site.example.com" ) );
        assertTrue( connectSrc.contains( "http://localhost:3001" ) );
    }

    @Test
    public void testExplicitPortKept()
    {
        processor.activate( Map.of( "nextjs.default.url", "https://site.example.com:8443/base" ) );

        processor.process( request, response );

        assertEquals( List.of( "https://site.example.com:8443" ), directive( "frame-src" ) );
    }

    @Test
    public void testConfigKeysMatchedCaseInsensitively()
    {
        processor.activate( Map.of( "NextJS.Default.URL", "https://site.example.com" ) );

        processor.process( request, response );

        assertEquals( List.of( "https://site.example.com" ), directive( "frame-src" ) );
    }

    @Test
    public void testDevFallsBackToLocalhostWhenNothingConfigured()
    {
        processor.activate( Map.of() );

        processor.process( request, response );

        assertEquals( List.of( "http://localhost:3000" ), directive( "frame-src" ) );
        assertEquals( List.of( "http://localhost:3000" ), directive( "connect-src" ) );
    }

    @Test
    public void testProdContributesNothingWhenNothingConfigured()
    {
        processor = new PreviewCspProcessor( RunMode.PROD );
        processor.activate( Map.of() );

        processor.process( request, response );

        assertEquals( "", request.getContentSecurityPolicy().serialize() );
    }

    @Test
    public void testProdContributesNothingWhenOnlyInvalidUrlsConfigured()
    {
        processor = new PreviewCspProcessor( RunMode.PROD );
        processor.activate( Map.of( "nextjs.default.url", "not a url" ) );

        processor.process( request, response );

        assertEquals( "", request.getContentSecurityPolicy().serialize() );
    }

    @Test
    public void testProdUsesExplicitConfigurations()
    {
        processor = new PreviewCspProcessor( RunMode.PROD );
        processor.activate( Map.of( "nextjs.default.url", "https://site.example.com" ) );

        processor.process( request, response );

        assertEquals( List.of( "https://site.example.com" ), directive( "frame-src" ) );
        assertEquals( List.of( "https://site.example.com" ), directive( "connect-src" ) );
    }

    @Test
    public void testInvalidUrlSkippedValidOnesApplied()
    {
        processor.activate( Map.of( "nextjs.default.url", "not a url", //
                                    "nextjs.production.url", "https://site.example.com" ) );

        processor.process( request, response );

        assertEquals( List.of( "https://site.example.com" ), directive( "frame-src" ) );
    }

    @Test
    public void testNonUrlConfigKeysIgnored()
    {
        processor.activate( Map.of( "nextjs.default.secret", "https://not-a-url-entry.example.com", //
                                    "nextjs.default.url", "https://site.example.com" ) );

        processor.process( request, response );

        assertEquals( List.of( "https://site.example.com" ), directive( "frame-src" ) );
    }

    @Test
    public void testReturnsSameResponseInstance()
    {
        processor.activate( Map.of() );

        assertSame( response, processor.process( request, response ) );
    }
}
