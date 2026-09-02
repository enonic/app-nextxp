var httpClient = require('/lib/http-client');
var cacheLib = require('/lib/cache');

var LEAD_SLASH_REGEX = /^\/*/;

var mappingsCache = cacheLib.newCache({
    size: 100,
    expire: 86400,
});

function fetchMappings(serverUrl, encryptedPayload) {
    let url = serverUrl.replace(/\/*$/, '') + '/api/mappings';
    if (encryptedPayload) {
        url += '?xp=' + encryptedPayload;
    }
    let mappings;
    try {
        const response = httpClient.request({
            url: url,
            method: 'GET',
            headers: {
                'Accept': 'application/json',
            },
            connectionTimeout: 5000,
            readTimeout: 10000,
        });

        if (response.status === 200) {
            const body = response.body;
            mappings = JSON.parse(body).mappings;
            log.debug(`Fetched mappings from "${url}":\n${JSON.stringify(body, null, 2)}`);
        } else {
            log.error(`Error fetching mappings from "${url}": [${response.status}] ${response.statusText}`);
        }

    } catch (e) {
        log.error(`Error fetching mappings from "${url}": ${e}`);
    }
    return mappings || [];
}

function getMappings(serverUrl, encryptedPayload) {
    return mappingsCache.get(serverUrl, function () {
        return fetchMappings(serverUrl, encryptedPayload);
    });
}

function toResolverConfig(serverConfig, mappings) {
    var baseUrl = serverConfig.url.replace(/\/*$/, '');
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
