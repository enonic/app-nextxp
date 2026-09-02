var configLib = require('/lib/export/config');

exports.get = function (request) {
    var configs = configLib.listConfigurations();
    return {
        status: 200,
        body: JSON.stringify({
            hits: configs.map(function (config) {
                var name = config.name;
                return {
                    id: name,
                    displayName: name.charAt(0).toUpperCase() + name.substring(1),
                    description: config.url
                };
            }),
            count: configs.length,
            total: configs.length,
        }),
        contentType: 'application/json'
    };
};
