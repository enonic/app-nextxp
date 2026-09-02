const contextLib = require('/lib/xp/context');
const portalLib = require('/lib/xp/portal');
const contentLib = require('/lib/xp/content');
const httpClient = require('/lib/http-client');
const i18nLib = require('/lib/xp/i18n');

const WIDGET_HEADER_NAME = 'enonic-widget-data';
const CMS_REPO_PREFIX = 'com.enonic.cms.';

function validateParams(params) {
    const id = params.contentId;
    const path = params.contentPath;
    const type = params.type;
    const branch = params.branch || 'master';
    const repository = params.repo;
    const auto = params.auto === 'true';
    const mode = params.mode || 'preview';
    const archive = params.archive === 'true';

    if (!id || !path || !repository) {
        const text = `Missing required parameter: ${!id ? 'contentId' : !path ? 'contentPath' : 'repo'}`;
        log.error(text);
        throw new Error(text);
    }

    return { id, path, type, branch, repository, auto, mode, archive };
}

function widgetResponse(status, data) {
    const response = {
        status,
        contentType: 'application/json',
    };

    if (data) {
        addData(response, data);
    }

    return response;
}

function redirectResponse(url, data) {
    const response = {
        redirect: encodeURI(url),
        contentType: 'text/html',
    };

    if (data) {
        addData(response, data);
    }

    return response;
}

function addData(response, data) {
    if (!response.body) {
        // there must be body to add headers
        response.body = '';
    }
    if (!response.headers) {
        response.headers = {};
    }
    response.headers[WIDGET_HEADER_NAME] = JSON.stringify(data);
}

function i18nFn(req) {
    let locales = [];
    if (req && req.locales) {
        locales = forceArray(req.locales);
    }
    return function (key) {
        return i18nLib.localize({
            key,
            bundles: ['i18n/phrases'],
            locale: locales,
        });
    };
}

function forceArray(value) {
    if (value === undefined || value === null) {
        return [];
    }
    return Array.isArray(value) ? value : [value];
}

function isArchiveContext(context) {
    return context.attributes && (context.attributes.contentRootPath === contentLib.ARCHIVE_ROOT_PATH);
}

function switchContext(repository, branch, archive, successCallback, errorCallback) {
    const context = contextLib.get();

    if (context.repository !== repository || context.branch !== branch || isArchiveContext(context) !== archive) {
        try {
            const newContext = {
                principals: ['role:system.admin'],
                repository,
                branch,
            };

            if (!!archive) {
                newContext.attributes = {
                    contentRootPath: contentLib.ARCHIVE_ROOT_PATH,
                };
            }

            return contextLib.run(newContext, function () {
                return successCallback();
            });
        } catch (e) {
            return errorCallback(e);
        }
    } else {
        return successCallback();
    }
}

function fetchSite(repository, branch, key, archive) {
    return switchContext(repository, branch, archive, function () {
        try {
            if (key) {
                return contentLib.getSite({ key });
            }

            return portalLib.getSite();

        } catch (e) {
            log.error(`Failed to fetch site: ${e.message}`);
            return null;
        }
    }, function (e) {
        log.error(`Failed to switch context: ${e.message}`);
        throw e;
    });
}

function pageUrl(repository, branch, path, archive) {
    return switchContext(repository, branch, archive, function () {
        try {
            return portalLib.pageUrl({
                path: path || '',
                type: 'server',
            });
        } catch (e) {
            log.error(`Failed to fetch site: ${e.message}`);
            return null;
        }
    }, function (e) {
        log.error(`Failed to switch context: ${e.message}`);
        throw e;
    });
}

function queryContent(contextParams, queryParams) {
    return switchContext(contextParams.repository, contextParams.branch, contextParams.archive, function () {
        return contentLib.query(queryParams);
    }, function (e) {
        log.error(`Failed to switch context: ${e.message}`);
        throw e;
    });
}

function fetchContent(repository, branch, key, archive) {
    return switchContext(repository, branch, archive, function () {
        try {
            if (key) {
                return contentLib.get({ key });
            } else {
                return portalLib.getContent();
            }
        } catch (e) {
            log.error(`Failed to fetch content: ${e.message}`);
            return null;
        }
    }, function (e) {
        log.error(`Failed to switch context: ${e.message}`);
        throw e;
    });
}


function fetchHttp(url, method, headers) {
    return httpClient.request({
        url,
        method,
        headers: {
            'Cookie': headers['Cookie'],
            'Cache-Control': 'no-cache',
        },
        connectionTimeout: 1000,
        readTimeout: 5000,
    });
}

function getProjectName(repository) {
    if (repository && repository.indexOf(CMS_REPO_PREFIX) === 0) {
        return repository.substring(CMS_REPO_PREFIX.length);
    }
    return repository || 'default';
}

/**
 * Builds the Next.js URL with a single encrypted `xp` query param.
 * @param {string} mappingUrl - resolved content URL from UrlMappingsResolver
 * @param {string} encryptedPayload - base64url-encoded AES-GCM encrypted blob
 * @returns {string}
 */
function buildNextUrl(mappingUrl, encryptedPayload) {
    if (!encryptedPayload) {
        return mappingUrl;
    }
    const separator = mappingUrl.indexOf('?') === -1 ? '?' : '&';
    return mappingUrl + separator + 'xp=' + encodeURIComponent(encryptedPayload);
}

exports.redirectResponse = redirectResponse;
exports.widgetResponse = widgetResponse;
exports.validateParams = validateParams;
exports.switchContext = switchContext;
exports.fetchContent = fetchContent;
exports.queryContent = queryContent;
exports.fetchSite = fetchSite;
exports.pageUrl = pageUrl;
exports.fetchHttp = fetchHttp;
exports.i18nFn = i18nFn;
exports.getProjectName = getProjectName;
exports.buildNextUrl = buildNextUrl;
