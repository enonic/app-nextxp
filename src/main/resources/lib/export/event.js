var eventLib = require('/lib/xp/event');
var httpClientLib = require('/lib/http-client');
var projectLib = require('/lib/xp/project');
var contextLib = require('/lib/xp/context');
var nodeLib = require('/lib/xp/node');
var clusterLib = require('/lib/xp/cluster');
var contentLib = require('/lib/xp/content');

var configLib = require('./config');
var widgetLib = require('./widget');

var debouncer = __.newBean('com.enonic.app.preview.nextjs.DebounceExecutor');
const payloadEncoder = __.newBean('com.enonic.app.preview.nextjs.PayloadEncoder');

var XP_PROJECT_ID_HEADER = 'Content-Studio-Project';
var CMS_REPO_PREFIX = 'com.enonic.cms.';

var REPOS = [];
var OLD_PATHS_CACHE = [];

function subscribe() {
    REPOS.push.apply(REPOS, queryNextjsRepos());

    subscribeToNodeEvents();
    subscribeToRepoEvents();
}

function shouldHandleEvent() {
    return clusterLib.isLeader();
}

function queryNextjsRepos() {
    var currentContext = contextLib.get();
    if (currentContext.authInfo.principals.indexOf('role:system.admin') < 0) {
        try {
            return contextLib.run({
                principals: ['role:system.admin'],
            }, function () {
                return queryNextjsReposInContext();
            });
        } catch (e) {
            log.error('Failed to query nextjs repos: ' + e.message);
            return [];
        }
    } else {
        return queryNextjsReposInContext();
    }
}

function queryNextjsReposInContext() {
    var sources = projectLib.list().map(function (repo) {
        return {
            repoId: CMS_REPO_PREFIX + repo.id,
            branch: 'master',
            principals: ['role:system.admin'],
        };
    });

    var sitesQueryResult = nodeLib.multiRepoConnect({ sources: sources }).query({
        start: 0,
        count: 999,
        query: 'type = \'portal:site\'',
        filters: {
            hasValue: {
                'field': 'data.siteConfig.applicationKey',
                'values': [app.name],
            },
        },
    });

    return sitesQueryResult.hits.map(function (site) {
        return site.repoId;
    });
}

function refreshNextjsRepos() {
    REPOS.length = 0;
    REPOS.push.apply(REPOS, queryNextjsRepos());
    log.debug('Updated content event repos: [' + REPOS + ']');
}

function subscribeToRepoEvents() {
    eventLib.listener({
        type: 'repository.*',
        localOnly: false,
        callback: function (event) {
            log.debug('Got [' + event.type + '] event for: ' + (event.data && event.data.id));
            refreshNextjsRepos();
        },
    });
    log.info('Subscribed to repository update events...');
}

function subscribeToNodeEvents() {
    eventLib.listener({
        type: 'node.*',
        localOnly: false,
        callback: function (event) {

            if (!shouldHandleEvent()) {
                log.debug('Got [' + event.type + '] event: leaving it to master node');
                return;
            }

            log.debug('Got [' + event.type + '] event: ' + JSON.stringify(event, null, 2));

            var reposUpdated = false;
            for (var i = 0; i < event.data.nodes.length; i++) {
                var node = event.data.nodes[i];
                var isMaster = node.branch === 'master';
                var isMove = isMoveEvent(event);

                if (!node.path.startsWith('/content/') || !isMaster && !isMove) {
                    continue;
                }

                if (isMaster && !reposUpdated) {
                    var isSitePushed = isSitePublished(event.type, node);
                    if (isSitePushed) {
                        reposUpdated = true;
                        refreshNextjsRepos();
                    }
                }

                if (REPOS.indexOf(node.repo) >= 0) {
                    if (isMove) {
                        OLD_PATHS_CACHE.push({
                            id: node.id,
                            path: node.path,
                            repo: node.repo,
                        });
                    }
                    if (isMaster) {
                        sendRevalidateAll(node.id, node.path, node.repo);
                        OLD_PATHS_CACHE.forEach(function (val) {
                            sendRevalidateNode(val.id, val.path, val.repo);
                        });
                        OLD_PATHS_CACHE.length = 0;
                        break;
                    }
                }
            }
        },
    });
    log.info('Subscribed to content update events for repos: ' + REPOS);
}

function isMoveEvent(event) {
    return event.type === 'node.moved' || event.type === 'node.renamed';
}

function isSitePublished(type, node) {
    if (type !== 'node.pushed') {
        return false;
    }

    var repo = nodeLib.connect({
        repoId: node.repo,
        branch: 'master',
    });

    var nodeData = repo && repo.get({
        key: node.id,
    });

    return nodeData && nodeData.type === 'portal:site';
}

function getSite(pathOrId, repoId) {
    var context = contextLib.get();
    if (context.repository !== repoId) {
        try {
            return contextLib.run({
                principals: ['role:system.admin'],
                repository: repoId,
            }, function () {
                return contentLib.getSite({ key: pathOrId || '/' });
            });
        } catch (e) {
            log.error('Failed to get site config: ' + e.message);
        }
    } else {
        return contentLib.getSite({ key: pathOrId || '/' });
    }
}

function sendRevalidateAll(nodeId, nodePath, repoId) {
    var site = getSite(nodeId, repoId);
    debouncer.debounce(function () {
        sendRevalidateRequest(null, site, repoId);
    }, 500);
}

function sendRevalidateNode(nodeId, nodePath, repoId) {
    var contentPath = nodePath.replace(/\/content\/[^\s\/]+/, '');
    if (!contentPath || contentPath.trim().length === 0) {
        contentPath = '/';
    }

    var site = getSite(nodeId, repoId);
    sendRevalidateRequest(contentPath, site, repoId);
}

function sendRevalidateRequest(contentPath, site, repoId) {
    log.debug('Requesting revalidation of [' + (contentPath || 'everything') + ']...');

    var serverConfig = configLib.getServerConfig(site);
    var projectName = widgetLib.getProjectName(repoId);

    const encryptedPayload = payloadEncoder.encode(JSON.stringify({
        xpProject: widgetLib.getProjectName(repoId),
    }), serverConfig.secret);

    var response = httpClientLib.request({
        method: 'GET',
        url: serverConfig.url + '/api/revalidate',
        connectionTimeout: 5000,
        readTimeout: 5000,
        headers: {
            [XP_PROJECT_ID_HEADER]: projectName,
        },
        queryParams: {
            path: contentPath,
            xp: encryptedPayload,
        },
        followRedirects: true,
    });

    if (response.status !== 200) {
        log.warning('Revalidation of \'' + (contentPath || 'everything') + '\' status: ' + response.status);
    } else {
        log.debug('Revalidation of [' + (contentPath || 'everything') + '] done');
    }
}

exports.subscribe = subscribe;
