package com.enonic.app.preview.nextjs;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.apache.commons.text.lookup.StringLookup;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.Mixin;
import com.enonic.xp.content.Mixins;
import com.enonic.xp.data.Property;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.schema.mixin.MixinName;
import com.enonic.xp.site.Site;

public class ContentFieldAccessor
    implements StringLookup
{
    private final Content content;

    private final Map<String, String> customFields = new HashMap<>();

    private static final String ID_PROPERTY = "_id";

    private static final String NAME_PROPERTY = "_name";

    private static final String PATH_PROPERTY = "_path";

    private static final String TYPE_PROPERTY = "type";

    private static final String DISPLAY_NAME_PROPERTY = "displayName";

    private static final String LANGUAGE_PROPERTY = "language";

    private static final String VALID_PROPERTY = "valid";

    private static final String DATA_PROPERTY_PREFIX = "data.";

    private static final String XDATA_PROPERTY_PREFIX = "x.";

    public ContentFieldAccessor( final Content content )
    {
        this.content = content;
    }

    public ContentFieldAccessor addField( final String key, final String value )
    {
        customFields.put( key, value );
        return this;
    }

    public static String getSiteRelativePath( final String path, final Content content, final Site site )
    {
        if ( content.isSite() )
        {
            return "/";
        }

        if ( site == null )
        {
            return path;
        }

        return path.substring( site.getPath().toString().length() );
    }

    public boolean matches( final String condition )
    {
        Map.Entry<String, String> entry = parse( condition );
        return valueMatches( trimQuotes( entry.getValue() ), getValue( entry.getKey() ) );
    }

    public String lookup( final String key )
    {
        final String value = getValue( key );
        return value == null ? "" : value;
    }

    public static Map.Entry<String, String> parse( String expression )
    {
        int index = expression.indexOf( ":" );
        if ( index == -1 )
        {
            throw new IllegalArgumentException( "Invalid match expression: " + expression );
        }
        else
        {
            String id = expression.substring( 0, index ).trim();
            String value = expression.substring( index + 1 ).trim();
            if ( id.isBlank() )
            {
                throw new IllegalArgumentException( "Invalid match expression: " + expression );
            }
            else
            {
                return Map.entry( id, value );
            }
        }
    }

    private boolean valueMatches( final String pattern, final String value )
    {
        try
        {
            return Pattern.compile( pattern ).matcher( value ).matches();
        }
        catch ( PatternSyntaxException e )
        {
            return false;
        }
    }

    private String trimQuotes( final String value )
    {
        final int length = value.length();
        if ( ( length > 1 ) && ( value.charAt( 0 ) == '\'' ) && ( value.charAt( length - 1 ) == '\'' ) )
        {
            return value.substring( 1, length - 1 );
        }

        return value;
    }

    private String getValue( final String key )
    {
        if ( content == null )
        {
            return null;
        }

        final String custom = customFields.get( key );
        if ( custom != null )
        {
            return custom;
        }

        if ( ID_PROPERTY.equals( key ) )
        {
            return content.getId().toString();
        }
        else if ( NAME_PROPERTY.equals( key ) )
        {
            return content.getName().toString();
        }
        else if ( PATH_PROPERTY.equals( key ) )
        {
            return content.getPath().toString();
        }
        else if ( TYPE_PROPERTY.equals( key ) )
        {
            return content.getType().toString();
        }
        else if ( DISPLAY_NAME_PROPERTY.equals( key ) )
        {
            return content.getDisplayName();
        }
        else if ( LANGUAGE_PROPERTY.equals( key ) )
        {
            return content.getLanguage() == null ? "" : content.getLanguage().toLanguageTag();
        }
        else if ( VALID_PROPERTY.equals( key ) )
        {
            return String.valueOf( content.isValid() );
        }
        else if ( key.startsWith( DATA_PROPERTY_PREFIX ) )
        {
            final String dataPath = key.substring( DATA_PROPERTY_PREFIX.length() );
            final Property prop = content.getData().getProperty( dataPath );
            if ( prop == null || prop.getValue() == null )
            {
                return null;
            }

            return prop.getString();
        }
        else if ( key.startsWith( XDATA_PROPERTY_PREFIX ) )
        {
            final String dataPath = key.substring( XDATA_PROPERTY_PREFIX.length() );

            final String appPrefix;
            final String mixinName;
            final String propertyName;

            final int firstIndex = dataPath.indexOf( '.' );
            if ( firstIndex == -1 )
            {
                appPrefix = dataPath;
                mixinName = "";
                propertyName = "";
            }
            else
            {
                appPrefix = dataPath.substring( 0, firstIndex );
                final int secondIndex = dataPath.indexOf( '.', firstIndex + 1 );
                mixinName = secondIndex == -1 ? dataPath.substring( firstIndex + 1 ) : dataPath.substring( firstIndex + 1, secondIndex );
                propertyName = secondIndex == -1 ? "" : dataPath.substring( secondIndex + 1 );
            }

            final PropertyTree xData = getXData( content.getMixins(), appPrefix, mixinName );
            if ( xData == null )
            {
                return null;
            }

            final Property prop = xData.getProperty( propertyName );
            if ( prop == null || prop.getValue() == null )
            {
                return null;
            }

            return prop.getString();
        }

        return null;
    }

    private PropertyTree getXData( final Mixins xDatas, final String appPrefix, final String name )
    {
        if ( xDatas == null )
        {
            return null;
        }
        try
        {
            final ApplicationKey app = Mixin.fromApplicationPrefix( appPrefix );
            final MixinName xDataName = MixinName.from( app, name );
            final Mixin extraData = xDatas.getByName( xDataName );
            if ( extraData == null )
            {
                return null;
            }
            return extraData.getData();
        }
        catch ( Exception e )
        {
            return null;
        }
    }
}
