package com.enonic.app.preview.nextjs;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.enonic.xp.app.Application;
import com.enonic.xp.app.ApplicationKey;
import com.enonic.xp.content.Content;
import com.enonic.xp.content.ContentId;
import com.enonic.xp.content.ContentName;
import com.enonic.xp.content.ContentPath;
import com.enonic.xp.content.ContentService;
import com.enonic.xp.content.Mixin;
import com.enonic.xp.content.Mixins;
import com.enonic.xp.data.PropertySet;
import com.enonic.xp.data.PropertyTree;
import com.enonic.xp.project.Project;
import com.enonic.xp.project.ProjectName;
import com.enonic.xp.project.ProjectService;
import com.enonic.xp.schema.content.ContentTypeName;
import com.enonic.xp.schema.mixin.MixinName;
import com.enonic.xp.script.ScriptValue;
import com.enonic.xp.script.bean.BeanContext;
import com.enonic.xp.site.Site;
import com.enonic.xp.site.SiteConfig;
import com.enonic.xp.site.SiteConfigs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class UrlMappingsResolverTest
{
    private Content content;

    private Site site;

    private Project project;

    private UrlMappingsResolver resolver;

    private ContentService contentService;

    private ProjectService projectService;

    private ApplicationKey appKey;

    @BeforeEach
    void setUp()
    {
        project = mock( Project.class );
        site = mock( Site.class );
        final Application app = mock( Application.class );
        appKey = ApplicationKey.from( "test.app" );
        when( app.getKey() ).thenReturn( appKey );

        when( project.getName() ).thenReturn( ProjectName.from( "test-project" ) );

        when( site.getId() ).thenReturn( ContentId.from( "site-id" ) );
        when( site.getType() ).thenReturn( ContentTypeName.from( "app:site" ) );
        when( site.getPath() ).thenReturn( ContentPath.from( "/site" ) );
        when( site.getName() ).thenReturn( ContentName.from( "site-name" ) );
        when( site.isSite() ).thenReturn( true );
        when( site.getData() ).thenReturn( new PropertyTree() );

        content = mock( Content.class );
        when( content.getId() ).thenReturn( ContentId.from( "content-id" ) );
        when( content.getName() ).thenReturn( ContentName.from( "content-name" ) );
        when( content.getPath() ).thenReturn( ContentPath.from( "/site/content" ) );
        when( content.getType() ).thenReturn( ContentTypeName.from( "app:article" ) );
        when( content.isSite() ).thenReturn( false );
        when( content.getData() ).thenReturn( new PropertyTree() );
        when( content.getMixins() ).thenReturn( Mixins.create().build() );

        contentService = mock( ContentService.class );
        projectService = mock( ProjectService.class );
        when( contentService.getById( ContentId.from( "content-id" ) ) ).thenReturn( content );
        when( contentService.getNearestSite( ContentId.from( "content-id" ) ) ).thenReturn( site );
        when( projectService.get( any( ProjectName.class ) ) ).thenReturn( project );

        final BeanContext bc = mock( BeanContext.class );
        when( bc.getApplicationKey() ).thenReturn( appKey );
        when( bc.getService( ContentService.class ) ).thenReturn( () -> contentService );
        when( bc.getService( ProjectService.class ) ).thenReturn( () -> projectService );
        when( bc.getBinding( com.enonic.xp.portal.PortalRequest.class ) ).thenReturn( () -> null );

        resolver = new UrlMappingsResolver();
        resolver.initialize( bc );
    }

    @Test
    public void testResolveNoContent()
    {
        Map<String, String> result = resolver.resolve( createParams( "absent-id", "/test/path" ), createMappings( "config1" ) );
        assertNull( result );
    }

    @Test
    public void testResolveNoProjectOrSiteConfig()
    {
        when( contentService.getNearestSite( ContentId.from( "content-id" ) ) ).thenReturn( null );

        Map<String, String> result = resolver.resolve( createParams(), mock( ScriptValue.class ) );
        assertNotNull( result );
        assertEquals( "http://localhost:3000/site/content", result.get( "url" ) );
        assertEquals( "http://localhost:3000", result.get( "baseUrl" ) );
    }

    @Test
    public void testResolveNoMappingItems()
    {
        Map<String, String> result = resolver.resolve( createParams(), mock( ScriptValue.class ) );
        assertNotNull( result );
        assertEquals( "http://localhost:3000/site/content", result.get( "url" ) );
        assertEquals( "http://localhost:3000", result.get( "baseUrl" ) );
    }

    @Test
    public void testContentIsSiteMatch()
    {
        when( site.getId() ).thenReturn( ContentId.from( "content-id" ) );
        when( site.getType() ).thenReturn( ContentTypeName.from( "app:product" ) );
        when( site.getPath() ).thenReturn( ContentPath.from( "/features/meta.txt" ) );

        final PropertyTree contentData = new PropertyTree();
        PropertySet productSet = contentData.addSet( "product" );
        productSet.setString( "category", "foo" );
        when( site.getData() ).thenReturn( contentData );

        when( contentService.getById( ContentId.from( "content-id" ) ) ).thenReturn( site );

        Map<String, String> result = resolver.resolve( createParams( "content-id", "/site/products/p1" ), createMappings( "default" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/baz/site-name", result.get( "url" ) );
        assertEquals( "http://localhost:8080", result.get( "baseUrl" ) );
        assertEquals( "53cr3t%21", result.get( "secret" ) );
    }

    @Test
    public void testMultipleConditionsMatch()
    {
        when( content.getType() ).thenReturn( ContentTypeName.from( "app:product" ) );
        when( content.getPath() ).thenReturn( ContentPath.from( "/features/meta.json" ) );

        PropertyTree contentData = new PropertyTree();
        PropertySet productSet = contentData.addSet( "product" );
        productSet.setString( "category", "foo" );
        when( content.getData() ).thenReturn( contentData );

        PropertyTree siteData = new PropertyTree();
        addAppSpecificConfig( siteData, "config1" );
        when( site.getData() ).thenReturn( siteData );

        Map<String, String> result = resolver.resolve( createParams( "content-id", "/site/products/p1" ), createMappings( "config1" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/bar/foo/", result.get( "url" ) );
        assertEquals( "http://localhost:8080", result.get( "baseUrl" ) );
    }

    @Test
    public void testQueryParamsMatch()
    {
        when( content.getType() ).thenReturn( ContentTypeName.from( "app:product" ) );
        when( content.getPath() ).thenReturn( ContentPath.from( "/features/meta.json" ) );

        final PropertyTree xData = new PropertyTree();
        xData.setString( "prop", "x-data-value" );
        Mixin extraData = new Mixin( MixinName.from( "app:mixin" ), xData );
        when( content.getMixins() ).thenReturn( Mixins.create().add( extraData ).build() );

        Map<String, String> result =
            resolver.resolve( createParams( "content-id", "/site/products/p1?category=foo&key=123" ), createMappings( "default" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/qux/x-data-value/", result.get( "url" ) );
        assertEquals( "http://localhost:8080", result.get( "baseUrl" ) );
    }

    @Test
    public void testContentPathMatchFromProjectConfig()
    {
        PropertyTree dataTree = new PropertyTree();
        PropertySet productSet = dataTree.addSet( "product" );
        productSet.setString( "category", "foo" );
        when( content.getData() ).thenReturn( dataTree );
        when( content.getType() ).thenReturn( ContentTypeName.from( "app:product" ) );
        when( content.getPath() ).thenReturn( ContentPath.from( "/features/meta.txt" ) );

        when( contentService.getNearestSite( ContentId.from( "content-id" ) ) ).thenReturn( null );

        when( project.getSiteConfigs() ).thenReturn( createSiteConfigs( "config1" ) );

        Map<String, String> result = resolver.resolve( createParams( "content-id", "/api" ), createMappings( "config1" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/baz/content-name", result.get( "url" ) );
        assertEquals( "http://localhost:8080", result.get( "baseUrl" ) );
        assertEquals( "53cr3t%21", result.get( "secret" ) );
    }

    @Test
    public void testResolveProjectConfig()
    {
        when( contentService.getNearestSite( ContentId.from( "content-id" ) ) ).thenReturn( null );

        when( project.getSiteConfigs() ).thenReturn( createSiteConfigs( "config1" ) );

        Map<String, String> result = resolver.resolve( createParams(), createMappings( "config1" ) );

        assertNull( result );
    }

    @Test
    public void testSiteRelativePathTemplateField()
    {
        Map<String, String> result = resolver.resolve( createParams(), createMappings( "default" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/rel/content", result.get( "url" ) );
    }

    @Test
    public void testSiteRelativePathForSiteItself()
    {
        when( contentService.getById( ContentId.from( "content-id" ) ) ).thenReturn( site );

        Map<String, String> result = resolver.resolve( createParams( "content-id", "/site" ), createMappings( "default" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/rel/", result.get( "url" ) );
    }

    @Test
    public void testSiteRelativePathWithoutSite()
    {
        when( contentService.getNearestSite( ContentId.from( "content-id" ) ) ).thenReturn( null );

        Map<String, String> result = resolver.resolve( createParams( "content-id", "/content" ), createMappings( "default" ) );

        assertNotNull( result );
        assertEquals( "http://localhost:8080/rel/site/content", result.get( "url" ) );
    }

    private Map<String, Object> createParams()
    {
        return createParams( "content-id", "/site/content" );
    }

    private Map<String, Object> createParams( final String id, final String path )
    {
        Map<String, Object> params = new HashMap<>();
        params.put( "path", path );
        params.put( "id", id );
        params.put( "repository", "com.enonic.cms.wtf" );
        params.put( "branch", "master" );
        params.put( "archive", "false" );
        return params;
    }

    private SiteConfigs createSiteConfigs( final String configName )
    {
        final PropertyTree siteData = new PropertyTree();
        siteData.addString( "configName", configName );
        return SiteConfigs.from( SiteConfig.create().application( this.appKey ).config( siteData ).build() );
    }

    private void addAppSpecificConfig( final PropertyTree parent, final String configName )
    {
        PropertySet siteConfig = parent.addSet( "siteConfig" );
        siteConfig.setString( "applicationKey", appKey.toString() );
        siteConfig.addSet( "config" ).addString( "configName", configName );
    }

    private ScriptValue createMappings( final String configName )
    {
        final ScriptValue result = mock( ScriptValue.class );
        when( result.isObject() ).thenReturn( true );

        when( result.getKeys() ).thenReturn( Set.of( configName ) );

        final ScriptValue config = mock( ScriptValue.class );
        when( config.isObject() ).thenReturn( true );
        when( result.getMember( configName ) ).thenReturn( config );

        final String baseUrl = "http://localhost:8080";

        final ScriptValue list = mock( ScriptValue.class );
        when( list.isArray() ).thenReturn( true );
        when( config.getMember( "mappings" ) ).thenReturn( list );

        ScriptValue set1 = mock( ScriptValue.class );
        when( set1.isObject() ).thenReturn( true );
        when( set1.getMap() ).thenReturn( Map.of(
            UrlMapping.SOURCES_KEY, Arrays.asList( "type:app:product", "data.product.category:foo", "/products/.*" ),
            UrlMapping.TARGET_KEY, "/bar/${data.product.category}/${data.foo}/${foo.bar}",
            UrlMapping.BASE_URL_KEY, baseUrl
        ) );

        ScriptValue set2 = mock( ScriptValue.class );
        when( set2.isObject() ).thenReturn( true );
        when( set2.getMap() ).thenReturn( Map.of(
            UrlMapping.SOURCES_KEY, Arrays.asList( "_path:'/features/.*.txt'", "" ),
            UrlMapping.TARGET_KEY, "/baz/${_name}",
            UrlMapping.SECRET_KEY, "53cr3t!",
            UrlMapping.BASE_URL_KEY, baseUrl,
            UrlMapping.MATCH_ANY_KEY, false
        ) );

        ScriptValue set3 = mock( ScriptValue.class );
        when( set3.isObject() ).thenReturn( true );
        when( set3.getMap() ).thenReturn( Map.of(
            UrlMapping.SOURCES_KEY, Arrays.asList( "/products/p1\\?category=foo&key=\\d+", "data.non.existing.value:true" ),
            UrlMapping.TARGET_KEY, "/qux/${x.app.mixin.prop}/${x.app.mixin.foo}/${x.app.foo}/${x.foo.bar}",
            UrlMapping.BASE_URL_KEY, baseUrl,
            UrlMapping.MATCH_ANY_KEY, true
        ) );

        ScriptValue set4 = mock( ScriptValue.class );
        when( set4.isObject() ).thenReturn( true );
        when( set4.getMap() ).thenReturn( Map.of(
            UrlMapping.SOURCES_KEY, List.of(),
            UrlMapping.TARGET_KEY, "/nevermore",
            UrlMapping.BASE_URL_KEY, baseUrl
        ) );

        ScriptValue set5 = mock( ScriptValue.class );
        when( set5.isObject() ).thenReturn( true );
        when( set5.getMap() ).thenReturn( Map.of(
            UrlMapping.SOURCES_KEY, Arrays.asList( "/content", "/" ),
            UrlMapping.TARGET_KEY, "/rel${siteRelativePath}",
            UrlMapping.BASE_URL_KEY, baseUrl,
            UrlMapping.MATCH_ANY_KEY, true
        ) );

        when( list.getArray() ).thenReturn( List.of( set2, set3, set1, set4, set5 ) );

        return result;
    }
}
