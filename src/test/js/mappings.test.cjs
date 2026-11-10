const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {test} = require('node:test');
const vm = require('node:vm');

const source = fs.readFileSync(path.join(__dirname, '../../main/resources/lib/export/mappings.js'), 'utf8');
const mappings = [{sources: ['/.*'], target: '${siteRelativePath}'}];

function createModule(request) {
    const entries = new Map();
    const context = {
        exports: {},
        log: {debug() {}, error() {}},
        require(name) {
            if (name === '/lib/http-client') {
                return {request};
            }
            if (name === '/lib/cache') {
                return {
                    newCache() {
                        return {
                            get(key, loader) {
                                if (!entries.has(key)) {
                                    entries.set(key, loader());
                                }
                                return entries.get(key);
                            },
                        };
                    },
                };
            }
            throw new Error('Unexpected module: ' + name);
        },
    };
    vm.runInNewContext(source, context);
    return context.exports;
}

const failures = [
    ['network error', () => { throw new Error('Connection refused'); }],
    ['HTTP error', () => ({status: 503, statusText: 'Service Unavailable'})],
    ['invalid JSON', () => ({status: 200, body: '<html>Unavailable</html>'})],
    ['missing mappings', () => ({status:
 200, body: '{}'})],
    ['empty mappings', () => ({status: 200, body: '{"mappings":[]}'})],
    ['invalid mappings', () => ({status: 200, body: '{"mappings":{}}'})],
];

for (const [name, fail] of failures) {
    test('retries after ' + name + ' and caches recovered mappings', () => {
        let requests = 0;
        const module = createModule(() => {
            requests++;
            return requests === 1 ? fail() : {status: 200, body: JSON.stringify({mappings})};
        });

        assert.equal(JSON.stringify(module.getMappings('http://localhost:3000', 'project')), '[]');
        assert.equal(JSON.stringify(module.getMappings('http://localhost:3000', 'project')), JSON.stringify(mappings));
        assert.equal(JSON.stringify(module.getMappings('http://localhost:3000', 'project')), JSON.stringify(mappings));
        assert.equal(requests, 2);
    });
}

test('caches mappings separately per project', () => {
    const requests = [];
    const module = createModule((request) => {
        requests.push(request.url);
        return {status: 200, body: JSON.stringify({mappings})};
    });

    module.getMappings('http://localhost:3000/', 'first', 'first-payload');
    module.getMappings('http://localhost:3000/', 'second', 'second-payload');
    module.getMappings('http://localhost:3000/', 'first', 'first-payload');

    assert.deepEqual(requests, [
        'http://localhost:3000/api/mappings?xp=first-payload',
        'http://localhost:3000/api/mappings?xp=second-payload',
    ]);
});
