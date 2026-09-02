package com.enonic.app.preview.nextjs;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;

import org.apache.commons.text.StringSubstitutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentId;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.context.Context;
import com.enonic.xp.context.ContextAccessor;
import com.enonic.xp.context.ContextBuilder;
import com.enonic.xp.project.Project;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.repository.RepositoryId;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.script.bean.ScriptBean;
import com.enonic.xp.security.RoleKeys;
import com.enonic.xp.security.auth.AuthenticationInfo;
import com.enonic.xp.site.Site;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;
import com.enonic.xp.site.SiteConfigsDataSerializer;

import static com.enonic.xp.archive.ArchiveConstants.ARCHIVE_ROOT_PATH;
import static com.enonic.xp.content.ContentConstants.CONTENT_ROOT_PATH;


public final class UrlMappingsResolver
    implements ScriptBean
{
    private ApplicationKey appKey;

    private ContentService contentService;

    private ProjectService projectService;

    public Map<String, String> resolve( final Map<String, Object> params, final ScriptValue mappings )
    {
        return adminContext( params )
            .callWith( new MappingResolverCallable( params, this.parseConfigs( mappings ), this.appKey, this.contentService,
                                                    this.projectService ) );
    }

    private Map<String, List<UrlMapping>> parseConfigs( final ScriptValue configs )
    {
        final Map<String, List<UrlMapping>> result = new HashMap<>();
        if ( configs != null && configs.isObject() )
        {
            configs.getKeys().forEach( name -> {
                final ScriptValue scriptConfig = configs.getMember( name );
                final ScriptValue scriptMappings = scriptConfig.getMember( "mappings" );
                if ( scriptMappings != null && scriptMappings.isArray() )
                {
                    final List<UrlMapping> mappings = scriptMappings.getArray().stream().map( UrlMapping::fromScriptValue ).toList();
                    result.put( name, mappings );
                }
            } );
        }
        return result;
    }

    private Context adminContext( final Map<String, Object> params )
    {
        return ContextBuilder.from( ContextAccessor.current() )
            .branch( (String) params.get( "branch" ) )
            .repositoryId( (String) params.get( "repository" ) )
            .attribute( "contentRootPath",
                        !Boolean.parseBoolean( params.get( "archive" ).toString() ) ? CONTENT_ROOT_PATH : ARCHIVE_ROOT_PATH )
            .authInfo( AuthenticationInfo.copyOf( ContextAccessor.current().getAuthInfo() ).principals( RoleKeys.ADMIN ).build() )
            .build();
    }

    @Override
    public void initialize( final BeanContext beanContext )
    {
        this.contentService = beanContext.getService( ContentService.class ).get();
        this.projectService = beanContext.getService( ProjectService.class ).get();
        this.appKey = beanContext.getApplicationKey();
    }
}

final class MappingResolverCallable
    implements Callable<Map<String, String>>
{
    private Logger LOGGER = LoggerFactory.getLogger( MappingResolverCallable.class );

    private final ApplicationKey appKey;

    private final ProjectService projectService;

    private final ContentService contentService;

    private final Map<String, Object> params;

    private final Map<String, List<UrlMapping>> mappingsMap;

    private final UrlMapping DEFAULT_MAPPING = new UrlMapping( "http://localhost:3000", List.of( "/.*" ), "${_path}" );

    public MappingResolverCallable( final Map<String, Object> params,
                                    final Map<String, List<UrlMapping>> mappingsMap,
                                    final ApplicationKey appKey,
                                    final ContentService contentService,
                                    final ProjectService projectService )
    {
        this.params = params;
        this.mappingsMap = mappingsMap;
        this.appKey = appKey;
        this.contentService = contentService;
        this.projectService = projectService;
    }

    @Override
    public Map<String, String> call()
        throws Exception
    {
        final Content content = contentService.getById( ContentId.from( params.get( "id" ) ) );
        if ( content == null )
        {
            return null;
        }

        final Site nearestSite = content.isSite() ? (Site) content : this.contentService.getNearestSite( content.getId() );

        final String path = params.get( "path" ).toString();
        final String siteRelativePath = ContentFieldAccessor.getSiteRelativePath( path, content, nearestSite );
        final ContentFieldAccessor contentAccessor = new ContentFieldAccessor( content )
            .addField( "siteRelativePath",
                       ContentFieldAccessor.getSiteRelativePath( content.getPath().toString(), content, nearestSite ) );

        for ( final UrlMapping mapping : getMappingList( nearestSite, mappingsMap ) )
        {
            if ( mapping.matches( siteRelativePath, contentAccessor ) )
            {
                LOGGER.debug( "Matched {}", mapping );
                final String uriPath = new StringSubstitutor( contentAccessor ).replace( mapping.getTarget() );
                final String url = new URI( mapping.getBaseUrl() ).resolve( uriPath ).normalize().toString();

                final Map<String, String> result = new HashMap<>();
                result.put( "url", url );
                result.put( "baseUrl", mapping.getBaseUrl() );
                if ( mapping.getSecret() != null )
                {
                    result.put( "secret", URLEncoder.encode( mapping.getSecret(), StandardCharsets.UTF_8 ) );
                }
                return result;
            }
        }

        return null;
    }

    private List<UrlMapping> getMappingList( final Site nearestSite, final Map<String, List<UrlMapping>> mappingsMap )
    {
        final SiteConfig siteConfig = getSiteOrProjectAppConfig( nearestSite );

        List<UrlMapping> mappingList = null;
        if ( siteConfig != null )
        {
            final String configName = siteConfig.getConfig().getString( "configName" );
            if ( configName != null && !configName.isEmpty() )
            {
                mappingList = mappingsMap.get( configName );
                LOGGER.debug( "Found {} mapping(s) with \"{}\" config name", mappingList != null ? mappingList.size() : 0, configName );
            }
            else
            {
                LOGGER.debug( "No configName set in either site or project config" );
            }
        }

        // Should work without site config using default mapping
        if ( mappingList == null )
        {
            mappingList = Optional.ofNullable( mappingsMap.get( "default" ) ).orElse( List.of( DEFAULT_MAPPING ) );
            LOGGER.debug( "Found {} mapping(s) with \"default\" config name", mappingList.size() );
        }
        return mappingList;
    }

    private SiteConfig getSiteOrProjectAppConfig( final Site nearestSite )
    {
        SiteConfig config = null;
        if ( nearestSite != null )
        {
            config = SiteConfigsDataSerializer.fromData( nearestSite.getData().getRoot() ).get( this.appKey );
        }

        if ( config == null )
        {
            config = getProjectSiteConfigs().get( this.appKey );
        }

        return config;
    }

    private SiteConfigs getProjectSiteConfigs()
    {
        final RepositoryId repositoryId = ContextAccessor.current().getRepositoryId();

        return Optional.ofNullable( repositoryId != null ? ProjectName.from( repositoryId ) : null )
            .map( projectService::get )
            .map( Project::getSiteConfigs )
            .orElseGet( SiteConfigs::empty );
    }
}
