const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/admin/extensions/preview-next/preview-next.js'), 'utf8');

test('returns 418 without converting or resolving empty mappings', () => {
    const params = {repository: 'com.enonic.cms.project', branch: 'draft', id: 'content-id'};
    const context = {
        exports: {},
        log: {debug() {}},
        __: {
            newBean() {
                return {resolve() { assert.fail('Empty mappings must not reach the resolver'); }};
            },
            toScriptValue() { assert.fail('Empty mappings must not be converted'); },
        },
        require(name) {
            switch (name) {
                case '/lib/export/widget':
                    return {
                        validateParams() { return params; },
                        fetchSite() { return {}; },
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
