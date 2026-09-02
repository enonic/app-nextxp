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

function getServerConfig(site) {
    var configName = getConfigNameFromSite(site);
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

function getConfigNameFromSite(site) {
    var siteConfigs = forceArray(site && site.data && site.data.siteConfig);
    if (!siteConfigs.length) {
        return 'default';
    }

    for (var i = 0; i < siteConfigs.length; i++) {
        var datum = siteConfigs[i];
        if (datum.applicationKey === app.name) {
            return (datum.config && datum.config.configName) || 'default';
        }
    }

    return 'default';
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
