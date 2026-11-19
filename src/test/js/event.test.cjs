const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const eventSource = fs.readFileSync(path.join(__dirname, '../../main/resources/lib/export/event.js'), 'utf8');
const configSource = fs.readFileSync(path.join(__dirname, '../../main/resources/lib/export/config.js'), 'utf8');

const REPO = 'com.enonic.cms.my-project';
const SITE = {
    _id: 'site-id',
    data: {siteConfig: [{applicationKey: 'com.enonic.app.nextxp', config: {configName: 'production'}}]},
};

function setup(listenerContext) {
    const app = {
        name: 'com.enonic.app.nextxp',
        config: {
            'nextjs.default.url': 'http://localhost:3000',
            'nextjs.default.secret': 'defaultSecret',
            'nextjs.production.url': 'https://frontend.example.com',
            'nextjs.production.secret': 'productionSecret',
        },
    };
    const configContext = {exports: {}, app, log: {debug() {}, info() {}, warning() {}, error() {}}};
    vm.runInNewContext(configSource, configContext);

    const state = {listeners: {}, requests: [], errors: [], siteLookups: []};
    let current = listenerContext;

    const context = {
        exports: {}, app,
        log: {debug() {}, info() {}, warning() {}, error(message) { state.errors.push(message); }},
        __: {
            newBean(name) {
                if (name.endsWith('DebounceExecutor')) {
                    return {debounce(fn) { fn(); }};
                }
                return {encode(payload, secret) { return secret + ':' + payload; }};
            },
        },
        require(name) {
            switch (name) {
                case '/lib/xp/event':
                    return {listener(params) { state.listeners[params.type] = params.callback; }};
                case '/lib/http-client':
                    return {request(params) { state.requests.push(params); return {status: 200}; }};
                case '/lib/xp/project':
                    return {list() { return [{id: 'my-project'}]; }};
                case '/lib/xp/context':
                    return {
                        get() { return current; },
                        run(params, callback) {
                            const previous = current;
                            current = Object.assign({}, current, params);
                            try {
                                return callback();
                            } finally {
                                current = previous;
                            }
                        },
                    };
                case '/lib/xp/node':
                    return {
                        multiRepoConnect() { return {query() { return {hits: [{repoId: REPO}]}; }}; },
                        connect() { return {get() { return {type: 'base:folder'}; }}; },
                    };
                case '/lib/xp/cluster':
                    return {isLeader() { return true; }};
                case '/lib/xp/content':
                    return {
                        getSite(params) {
                            state.siteLookups.push({key: params.key, repository: current.repository, branch: current.branch});
                            // Like XP, a lookup without a repository and branch in the context fails
                            if (current.repository !== REPO || current.branch !== 'master') {
                                throw new Error('Error getting node');
                            }
                            return SITE;
                        },
                    };
                case './config':
                    return configContext.exports;
                case './widget':
                    return {getProjectName(repoId) { return repoId.replace('com.enonic.cms.', ''); }};
                default:
                    throw new Error('Unexpected require: ' + name);
            }
        },
    };
    vm.runInNewContext(eventSource, context);
    context.exports.subscribe();
    return state;
}

function pushed() {
    return {
        type: 'node.pushed',
        data: {nodes: [{id: 'content-id', path: '/content/site/page', branch: 'master', repo: REPO}]},
    };
}

for (const [name, listenerContext] of [
    ['without a repository or branch', {authInfo: {principals: ['role:system.admin']}}],
    ['with the repository but on the draft branch', {authInfo: {principals: ['role:system.admin']}, repository: REPO, branch: 'draft'}],
    ['with the repository but without a branch', {authInfo: {principals: ['role:system.admin']}, repository: REPO}],
    ['already on the master branch of the repository', {authInfo: {principals: ['role:system.admin']}, repository: REPO, branch: 'master'}],
]) {
    test('revalidation resolves the site on master when the event context is ' + name, () => {
        const state = setup(listenerContext);

        state.listeners['node.*'](pushed());

        assert.deepEqual(state.errors, []);
        assert.deepEqual(state.siteLookups, [{key: 'content-id', repository: REPO, branch: 'master'}]);
        assert.equal(state.requests.length, 1);
        assert.equal(state.requests[0].url, 'https://frontend.example.com/api/revalidate');
        assert.equal(state.requests[0].queryParams.xp, 'productionSecret:' + JSON.stringify({xpProject: 'my-project'}));
        assert.equal(state.requests[0].headers['Content-Studio-Project'], 'my-project');
    });
}
