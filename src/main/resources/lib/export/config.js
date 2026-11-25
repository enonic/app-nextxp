var contentLib = require('/lib/xp/content');
var contextLib = require('/lib/xp/context');

var CONFIG_REGEX = new RegExp('^nextjs\\.([^.]+)\\.(url|secret)$', 'i');

var CONFIGURATIONS;

function readConfigurations(force) {
    if (!force && CONFIGURATIONS) {
        return CONFIGURATIONS;
    }
    var appConfig = app.config || {};
    CONFIGURATIONS = Object.keys(appConfig).reduce(function (all, key) {
        if (!CONFIG_REGEX.test(key)) {
            return all;
        }

        var result = CONFIG_REGEX.exec(key);
        var name = result[1];
        var type = result[2];
        var configuration = all[name];
        if (!configuration) {
            configuration = {};
            all[name] = configuration;
        }
        configuration[type] = appConfig[key];

        return all;
    }, {});
    return CONFIGURATIONS;
}

function getServerConfig(site, project) {
    var configName = getConfigNameFromSite(site, project);
    var configs = readConfigurations();
    var config = configs[configName] || configs['default'];
    if (!config) {
        config = {
            url: 'http://localhost:3000',
            secret: 'mySecretKey',
        };
    }
    return config;
}

function forceArray(value) {
    if (value === undefined || value === null) {
        return [];
    }
    return Array.isArray(value) ? value : [value];
}

function getConfigNameFromSite(site, project) {
    var siteConfigs = forceArray(site && site.data && site.data.siteConfig)
        .concat(forceArray(project && project.siteConfig));
    if (!siteConfigs.length) {
        return 'default';
    }

    for (var i = 0; i < siteConfigs.length; i++) {
        var datum = siteConfigs[i];
        if (datum && datum.applicationKey === app.name) {
            return (datum.config && datum.config.configName) || 'default';
        }
    }

    return 'default';
}

function getSite(pathOrId, repoId, branch) {
    var context = contextLib.get();
    if (context.repository !== repoId || (branch && context.branch !== branch)) {
        try {
            return contextLib.run({
                principals: ['role:system.admin'],
                repository: repoId,
                branch: branch,
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

function listConfigurations() {
    var configs = readConfigurations();
    return Object.keys(configs).map(function (name) {
        return { name: name, url: configs[name].url };
    });
}

exports.readConfigurations = readConfigurations;
exports.getServerConfig = getServerConfig;
exports.listConfigurations = listConfigurations;
exports.getSite = getSite;
