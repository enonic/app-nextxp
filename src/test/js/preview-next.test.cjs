const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/admin/extensions/preview-next/preview-next.js'), 'utf8');

for (const siteConfig of [
    [{applicationKey: 'another.app'}, {applicationKey: 'com.enonic.app.nextxp'}],
    {applicationKey: 'com.enonic.app.nextxp'},
]) {
    test('returns 418 without converting or resolving empty mappings: ' + JSON.stringify(siteConfig), () => {
        const params = {repository: 'com.enonic.cms.project', branch: 'draft', id: 'content-id'};
        const context = {
            exports: {},
            app: {name: 'com.enonic.app.nextxp'},
            log: {debug() {}},
            __: {
                newBean() {
                    return {resolve() { assert.fail('Empty mappings must not reach the resolver'); }};
                },
                toScriptValue() { assert.fail('Empty mappings must not be converted'); },
            },
            require(name) {
                switch (name) {
                    case '/lib/xp/project':
                        return {get() { return null; }};
                    case '/lib/export/widget':
                        return {
                            validateParams() { return params; },
                            fetchSite() { return {data: {siteConfig}}; },
                            getProjectName() { return 'project'; },
                            widgetResponse(status) { return {status}; },
                        };
                    case '/lib/export/config':
                        return {getServerConfig() { return {url: 'http://localhost:3000'}; }};
                    case '/lib/export/mappings':
                        return {
                            getMappings() { return []; },
                            toResolverConfig() { assert.fail('Empty mappings must not be converted'); },
                        };
                    default:
                        throw new Error('Unexpected module: ' + name);
                }
            },
        };
        vm.runInNewContext(source, context);

        assert.deepEqual(context.exports.get({method: 'GET', params: {}}), {status: 418});
    });
}

const sitesWithoutApp = [
    ['no site', null],
    ['no site data', {}],
    ['no site config', {data: {}}],
    ['empty site config', {data: {siteConfig: []}}],
    ['another application', {data: {siteConfig: [{applicationKey: 'another.app'}]}}],
    ['single other application', {data: {siteConfig: {applicationKey: 'another.app'}}}],
];

for (const [name, site] of sitesWithoutApp) {
    test('returns 418 before reading server config or fetching mappings: ' + name, () => {
        const context = {
            exports: {},
            app: {name: 'com.enonic.app.nextxp'},
            log: {debug() {}},
            __: {newBean() { return {}; }},
            require(name) {
                switch (name) {
                    case '/lib/xp/project':
                        return {get() { return null; }};
                    case '/lib/export/widget':
                        return {
                            validateParams() { return {}; },
                            fetchSite() { return site; },
                            getProjectName() { return 'project'; },
                            widgetResponse(status) { return {status}; },
                        };
                    case '/lib/export/config':
                        return {getServerConfig() { assert.fail('Unconfigured sites must not read server config'); }};
                    case '/lib/export/mappings':
                        return {getMappings() { assert.fail('Unconfigured sites must not fetch mappings'); }};
                    default:
                        throw new Error('Unexpected module: ' + name);
                }
            },
        };
        vm.runInNewContext(source, context);

        assert.deepEqual(context.exports.get({method: 'GET', params: {}}), {status: 418});
    });
}
