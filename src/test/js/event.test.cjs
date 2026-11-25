const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/lib/export/event.js'), 'utf8');

test('a promoted follower dispatches only pending paths using their originating branches', () => {
    const repo = 'com.enonic.cms.project';
    const context = {repository: repo, branch: 'master', authInfo: {principals: ['role:system.admin']}};
    let leader = false;
    let hits = [];
    const callbacks = {};
    const queued = [];
    const requests = [];
    const modules = {
        '/lib/xp/event': {listener: ({type, callback}) => { callbacks[type] = callback; }},
        '/lib/xp/cluster': {isLeader: () => leader},
        '/lib/xp/project': {list: () => [{id: 'project'}]},
        '/lib/xp/node': {
            multiRepoConnect: () => ({query: () => ({hits})}),
            connect: () => ({get: ({key}) => ({type: key === 'site' ? 'portal:site' : 'page'})}),
        },
        '/lib/xp/context': {get: () => context},
        './config': {
            getSite(key, repoId, branch) {
                assert.equal(repoId, repo);
                return `${branch}/${key}`;
            },
            getServerConfig: site => ({url: 'https://example.test/' + site, secret: 'test'}),
        },
        './widget': {getProjectName: () => 'project'},
        '/lib/http-client': {request(req) { requests.push([req.url, req.queryParams.path]); return {status: 200}; }},
    };
    const sandbox = {exports: {}, app: {name: 'nextxp'}, log: {debug() {}, info() {}},
        require: name => modules[name], __: {newBean: () => ({debounce: fn => queued.push(fn), encode: () => 'encoded'})}};
    vm.runInNewContext(source, sandbox);
    sandbox.exports.subscribe();
    function emit(type, branch, id) {
        callbacks['node.*']({type, data: {nodes: [{repo, branch, id, path: '/content/site/' + id}]}});
    }

    hits = [{repoId: repo}];
    emit('node.pushed', 'master', 'site'); // Discover the repository while still a follower.
    emit('node.moved', 'draft', 'stale');
    emit('node.pushed', 'master', 'published'); // Clear stale paths without dispatching.
    emit('node.renamed', 'draft', 'pending');
    assert.equal(queued.length, 0);
    assert.deepEqual(requests, []);

    leader = true;
    emit('node.pushed', 'master', 'published');
    assert.equal(queued.length, 1);
    assert.deepEqual(requests, [['https://example.test/draft/pending/api/revalidate', '/pending']]);
    leader = false;
    queued[0]();
    assert.deepEqual(requests[1], ['https://example.test/master/published/api/revalidate', null]);
});
