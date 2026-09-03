package com.enonic.app.preview.nextjs;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.admin.extension.AdminExtensionResponseProcessor;
import com.enonic.xp.portal.PortalRequest;
import com.enonic.xp.portal.PortalResponse;
import com.enonic.xp.server.RunMode;

@Component(immediate = true, configurationPid = "com.enonic.app.nextxp",
    property = "key=com.enonic.app.nextxp:preview-next")
public class PreviewCspProcessor
    implements AdminExtensionResponseProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger( PreviewCspProcessor.class );

    private static final Pattern URL_CONFIG_KEY = Pattern.compile( "^nextjs\\.[^.]+\\.url$", Pattern.CASE_INSENSITIVE );

    private static final String FALLBACK_URL = "http://localhost:3000";

    private final RunMode runMode;

    private volatile List<String> origins = List.of();

    public PreviewCspProcessor()
    {
        this( RunMode.get() );
    }

    PreviewCspProcessor( final RunMode runMode )
    {
        this.runMode = runMode;
    }

    @Activate
    @Modified
    public void activate( final Map<String, Object> config )
    {
        final Set<String> result = new LinkedHashSet<>();
        config.forEach( ( key, value ) -> {
            if ( URL_CONFIG_KEY.matcher( key ).matches() )
            {
                final String origin = toOrigin( value );
                if ( origin != null )
                {
                    result.add( origin );
                }
                else
                {
                    LOGGER.warn( "Ignoring invalid Next.js url in config [{}]: {}", key, value );
                }
            }
        } );

        if ( result.isEmpty() && this.runMode == RunMode.DEV )
        {
            result.add( toOrigin( FALLBACK_URL ) );
        }

        this.origins = List.copyOf( result );
    }

    @Override
    public PortalResponse process( final PortalRequest request, final PortalResponse response )
    {
        if ( !this.origins.isEmpty() )
        {
            final String[] sources = this.origins.toArray( String[]::new );
            request.getContentSecurityPolicy().frameSrc( sources ).connectSrc( sources ).styleSrc( sources );
        }
        return response;
    }

    private static String toOrigin( final Object url )
    {
        try
        {
            final URI uri = new URI( url.toString().trim() );
            if ( uri.getScheme() == null || uri.getHost() == null )
            {
                return null;
            }
            final String origin = uri.getScheme() + "://" + uri.getHost();
            return uri.getPort() == -1 ? origin : origin + ":" + uri.getPort();
        }
        catch ( URISyntaxException e )
        {
            return null;
        }
    }
}
