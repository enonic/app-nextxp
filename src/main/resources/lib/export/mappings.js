var httpClient = require('/lib/http-client');
var cacheLib = require('/lib/cache');

var LEAD_SLASH_REGEX = /^\/*/;
var TRAIL_SLASH_REGEX = /\/*$/;

var mappingsCache = cacheLib.newCache({
    size: 100,
    expire: 86400,
});

function fetchMappings(serverUrl, encryptedPayload) {
    let url = serverUrl + '/api/mappings';
    if (encryptedPayload) {
        url += '?xp=' + encryptedPayload;
    }
    const response = httpClient.request({
        url: url,
        method: 'GET',
        headers: {
            'Accept': 'application/json',
        },
        connectionTimeout: 5000,
        readTimeout: 10000,
    });

    if (response.status !== 200) {
        throw new Error(`[${response.status}] ${response.statusText}`);
    }

    const mappings = JSON.parse(response.body).mappings || [];
    log.debug(`Fetched mappings from "${url}":\n${JSON.stringify(mappings, null, 2)}`);
    return mappings;
}

// Cached per XP project (a project maps to one Next.js server); a throwing loader is not cached by lib-cache
function getMappings(serverUrl, projectName, encryptedPayload) {
    var normalizedUrl = serverUrl.replace(TRAIL_SLASH_REGEX, '');
    try {
        return mappingsCache.get(projectName, function () {
            return fetchMappings(normalizedUrl, encryptedPayload);
        });
    } catch (e) {
        log.error(`Error fetching mappings from "${normalizedUrl}": ${e.message || e}`);
        return [];
    }
}

function toResolverConfig(serverConfig, mappings) {
    var baseUrl = serverConfig.url.replace(TRAIL_SLASH_REGEX, '');
    var secret = serverConfig.secret;

    return mappings.map(function (mapping) {
        var target = mapping.target || '';
        return {
            baseUrl: baseUrl,
            secret: secret,
            sources: mapping.sources || [],
            target: target.replace(LEAD_SLASH_REGEX, ''),
            matchAny: !!mapping.matchAny,
        };
    });
}

exports.getMappings = getMappings;
exports.toResolverConfig = toResolverConfig;
