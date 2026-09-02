package com.enonic.app.preview.nextjs;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.enonic.xp.script.ScriptValue;

public class UrlMapping
{
    public static final String SOURCES_KEY = "sources";

    public static final String TARGET_KEY = "target";

    public static final String MATCH_ANY_KEY = "matchAny";

    public static final String BASE_URL_KEY = "baseUrl";

    public static final String SECRET_KEY = "secret";

    private final String baseUrl;

    private final String secret;

    private final List<String> sources;

    private final String target;

    private final MatchStrategy matchStrategy;

    public UrlMapping( final String baseUrl, final List<String> source, final String target )
    {
        this( baseUrl, null, source, target, MatchStrategy.ALL );
    }

    public UrlMapping( final String baseUrl, final String secret, final List<String> source, final String target,
                       final MatchStrategy matchStrategy )
    {
        this.baseUrl = baseUrl;
        this.secret = secret;
        this.sources = source;
        this.target = target;
        this.matchStrategy = matchStrategy;
    }

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public String getSecret()
    {
        return secret;
    }

    public Iterable<String> getSources()
    {
        return sources;
    }

    public String getTarget()
    {
        return target;
    }

    public boolean matches( final String siteRelativePath,
                            final ContentFieldAccessor contentAccessor )
    {
        if ( this.sources == null || !this.sources.iterator().hasNext() )
        {
            return false;
        }

        for ( final String source : this.sources )
        {
            if ( source == null || source.isBlank() )
            {
                continue;
            }

            final boolean matches = matchSource( source, siteRelativePath, contentAccessor );

            if ( ( this.matchStrategy == MatchStrategy.ANY && matches ) ||
                ( this.matchStrategy == MatchStrategy.ALL && !matches ) )
            {
                return matches;
            }
        }

        return this.matchStrategy == MatchStrategy.ALL;
    }

    private boolean matchSource( final String source,
                                 final String relativePath,
                                 final ContentFieldAccessor contentAccessor )
    {
        try
        {
            return contentAccessor.matches( source );
        }
        catch ( Exception e )
        {
            // Source is not a content constraint, trying url pattern

            return Pattern.compile( source ).matcher( relativePath ).matches();
        }
    }

    public static UrlMapping fromScriptValue( final ScriptValue value )
    {
        Map<String, Object> map = value.getMap();
        return new UrlMapping( (String) map.get( BASE_URL_KEY ),
                               (String) map.get( SECRET_KEY ),
                               (List<String>) map.get( SOURCES_KEY ),
                               (String) map.get( TARGET_KEY ),
                               Boolean.TRUE.equals( map.get( MATCH_ANY_KEY ) ) ? MatchStrategy.ANY : MatchStrategy.ALL );
    }

    @Override
    public String toString()
    {
        return String.format( "UrlMapping{baseUrl='%s', secret=%s, sources=%s, target='%s', matchStrategy=%s}",
                              baseUrl, secret != null ? "'***'" : "null",
                              this.sources != null ? String.join( ", ", this.sources ) : List.of(), target, matchStrategy );
    }
}
