const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const previewSource = fs.readFileSync(path.join(__dirname, '../../main/resources/admin/extensions/preview-next/preview-next.js'), 'utf8');
const configSource = fs.readFileSync(path.join(__dirname, '../../main/resources/lib/export/config.js'), 'utf8');

for (const siteConfig of [
    [{applicationKey: 'com.enonic.app.nextxp', config: {configName: 'production'}}],
    {applicationKey: 'com.enonic.app.nextxp', config: {configName: 'production'}},
    [{applicationKey: 'com.enonic.app.nextxp', config: {}}],
    [],
    [{applicationKey: 'another.app'}],
]) {
    for (const site of [null, {data: {siteConfig: [{applicationKey: 'another.app'}]}}]) {
        test('uses project config ' + (site ? 'inside' : 'outside') + ' a site: ' + JSON.stringify(siteConfig), () => {
            const app = {
                name: 'com.enonic.app.nextxp',
                config: {
                    'nextjs.production.url': 'https://frontend.example.com',
                    'nextjs.default.url': 'http://localhost:3000',
                },
            };
            const configContext = {exports: {}, app};
            vm.runInNewContext(configSource, configContext);
            const configs = [].concat(siteConfig);
            const appConfig = configs.find((config) => config.applicationKey === app.name);
            let fetches = 0;
            const context = {
                exports: {}, app,
                log: {debug() {}, info() {}},
                __: {
                    newBean() { return {resolve() { return {url: 'https://frontend.example.com/page'}; }}; },
                    toScriptValue(value) { return value; },
                },
                require(name) {
                    switch (name) {
                        case '/lib/xp/project':
                            return {get(params) {
                                assert.equal(params.id, 'my-project');
                                return {siteConfig};
                            }};
                        case '/lib/export/widget':
                            return {
                                validateParams() { return {repository: 'com.enonic.cms.my-project', mode: 'inline'}; },
                                fetchSite() { return site; },
                                getProjectName() { return 'my-project'; },
                                widgetResponse(status, data) { return {status, data}; },
                                buildNextUrl(url) { return url; },
                            };
                        case '/lib/export/config':
                            return configContext.exports;
                        case '/lib/export/mappings':
                            return {
                                getMappings(url) {
                                    assert.ok(appConfig, 'Unconfigured projects must not fetch mappings');
                                    assert.equal(url, appConfig.config.configName ? 'https://frontend.example.com' : 'http://localhost:3000');
                                    fetches++;
                                    return [{sources: ['/.*'], target: '${_path}'}];
                                },
                                toResolverConfig(config, mappings) { return mappings; },
                            };
                        default:
                            throw new Error('Unexpected module: ' + name);
                    }
                },
            };
            vm.runInNewContext(previewSource, context);

            assert.equal(context.exports.get({method: 'GET', params: {}}).status, appConfig ? 200 : 418);
            assert.equal(fetches, appConfig ? 1 : 0);
        });
    }
}

test('site configuration takes precedence over project configuration', () => {
    const context = {
        exports: {},
        app: {
            name: 'com.enonic.app.nextxp',
            config: {
                'nextjs.site.url': 'https://site.example.com',
                'nextjs.project.url': 'https://project.example.com',
            },
        },
    };
    vm.runInNewContext(configSource, context);
    const site = {data: {siteConfig: [{applicationKey: context.app.name, config: {configName: 'site'}}]}};
    const project = {siteConfig: [{applicationKey: context.app.name, config: {configName: 'project'}}]};

    assert.equal(context.exports.getServerConfig(site, project).url, 'https://site.example.com');
});
